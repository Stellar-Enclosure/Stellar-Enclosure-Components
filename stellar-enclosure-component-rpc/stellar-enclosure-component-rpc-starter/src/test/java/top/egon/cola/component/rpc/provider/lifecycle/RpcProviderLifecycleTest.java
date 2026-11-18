package top.egon.cola.component.rpc.provider.lifecycle;

import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBeanScanner;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerFactory;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerInterceptor;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;
import top.egon.cola.component.rpc.support.RpcProviderTestFixtures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcProviderLifecycleTest {

    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void rejectsTheReservedGammaFallbackPortBeforeBinding() {
        RpcProviderServerFactory factory = new RpcProviderServerFactory();
        assertThatThrownBy(() -> factory.create("127.0.0.1", 19099, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved GAMMA fallback port");
    }

    @Test
    void serverFactoryExecutesInterceptorsInDeclaredOrder() throws Exception {
        List<String> order = new ArrayList<>();
        MethodDescriptor<Message, Message> method = echoMethod();
        ServerServiceDefinition service = ServerServiceDefinition.builder(method.getServiceName())
                .addMethod(method, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();
        Server server = new RpcProviderServerFactory().create(
                "127.0.0.1", 0, List.of(service),
                List.of(interceptor("first", order), interceptor("second", order))
        ).start();
        ManagedChannel channel = plaintextChannel(server.getPort());
        try {
            Message response = ClientCalls.blockingUnaryCall(
                    channel, method, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    StringValue.of("hello"));

            assertThat(response).isEqualTo(StringValue.of("hello"));
            assertThat(order).containsExactly("first", "second");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void productionH2cStartsWithoutCertificatesOrRegistryAndDrainsOnce() throws Exception {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
            RpcProviderMethodRegistry methods = scan(context);
            RpcProviderLifecycle lifecycle = lifecycle(methods, availability,
                    new RpcProviderServerFactory(), configuredProperties());
            lifecycle.start();
            ManagedChannel channel = plaintextChannel(lifecycle.boundPort());
            try {
                lifecycle.start();
                assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.READY);
                assertThat(lifecycle.isRunning()).isTrue();
                assertAvailable(methods, availability, true);
                Message response = ClientCalls.blockingUnaryCall(
                        channel, echoMethod(),
                        CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                        StringValue.of("hello"));
                assertThat(response).isEqualTo(StringValue.of("provider:hello"));

                AtomicInteger callbacks = new AtomicInteger();
                lifecycle.stop(callbacks::incrementAndGet);
                lifecycle.stop(callbacks::incrementAndGet);

                assertThat(callbacks).hasValue(1);
                assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.STOPPED);
                assertThat(lifecycle.isRunning()).isFalse();
                assertThat(lifecycle.boundPort()).isEqualTo(-1);
                assertAvailable(methods, availability, false);
                assertThatThrownBy(lifecycle::start).isInstanceOf(EgonRpcException.class)
                        .hasMessageContaining("not restartable");
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                lifecycle.stop();
            }
        }
    }

    @Test
    void failedListenerStartClosesPreparedServerAndLeavesNoAvailability() throws Exception {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            RpcProviderMethodRegistry methods = scan(context);
            RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
            Server server = mock(Server.class);
            when(server.start()).thenThrow(new IOException("bind failed"));
            RpcProviderLifecycle lifecycle = lifecycle(methods, availability,
                    factory(server), configuredProperties());

            assertThatThrownBy(lifecycle::start).isInstanceOf(EgonRpcException.class)
                    .hasRootCauseMessage("bind failed");

            assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.FAILED);
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(lifecycle.boundPort()).isEqualTo(-1);
            assertAvailable(methods, availability, false);
            verify(server).shutdownNow();
            assertThatThrownBy(lifecycle::start).isInstanceOf(EgonRpcException.class)
                    .hasMessageContaining("not restartable");
        }
    }

    @Test
    void drainClosesAvailabilityBeforeShutdownAndForcesAfterConfiguredTimeout() throws Exception {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            RpcProviderMethodRegistry methods = scan(context);
            RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
            Server server = mock(Server.class);
            when(server.start()).thenReturn(server);
            when(server.awaitTermination(100, TimeUnit.MILLISECONDS)).thenReturn(false);
            RpcProviderLifecycle lifecycle = lifecycle(methods, availability,
                    factory(server), configuredProperties());
            lifecycle.start();
            doAnswer(invocation -> {
                assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.DRAINING);
                assertThat(lifecycle.isRunning()).isFalse();
                assertAvailable(methods, availability, false);
                return server;
            }).when(server).shutdown();

            lifecycle.stop();

            var order = inOrder(server);
            order.verify(server).shutdown();
            order.verify(server).awaitTermination(100, TimeUnit.MILLISECONDS);
            order.verify(server).shutdownNow();
            assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.STOPPED);
        }
    }

    @Test
    void interruptedDrainRestoresInterruptAndForcesShutdown() throws Exception {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
            Server server = mock(Server.class);
            when(server.start()).thenReturn(server);
            when(server.awaitTermination(100, TimeUnit.MILLISECONDS))
                    .thenThrow(new InterruptedException("interrupted"));
            RpcProviderLifecycle lifecycle = lifecycle(scan(context), availability,
                    factory(server), configuredProperties());
            lifecycle.start();
            try {
                lifecycle.stop();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                verify(server).shutdownNow();
                assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.STOPPED);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void preservesNoProviderErrorSemantics() {
        RpcProviderLifecycle lifecycle = lifecycle(new RpcProviderMethodRegistry(List.of()),
                new RpcProviderAvailabilityRegistry(), new RpcProviderServerFactory(),
                configuredProperties());

        assertThatThrownBy(lifecycle::start).isInstanceOf(EgonRpcException.class)
                .hasMessageContaining("no RPC Provider bean");
        assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.FAILED);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void invalidListenerSettingsFailBeforeCreatingServer() {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            EgonRpcProperties properties = configuredProperties();
            properties.getProvider().setGracefulShutdownTimeoutMs(-1);
            RpcProviderServerFactory factory = mock(RpcProviderServerFactory.class);
            RpcProviderLifecycle lifecycle = lifecycle(scan(context),
                    new RpcProviderAvailabilityRegistry(), factory, properties);

            assertThatThrownBy(lifecycle::start).isInstanceOf(EgonRpcException.class)
                    .hasMessageContaining("shutdown timeout");
            assertThat(lifecycle.state()).isEqualTo(RpcProviderRuntimeState.FAILED);
            verify(factory, never()).create(anyString(), anyInt(), any(), any());
        }
    }

    @Test
    void rejectsTooSmallInboundMessageLimit() {
        assertThatThrownBy(() -> new RpcProviderServerFactory(1023))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1024");
    }

    private AnnotationConfigApplicationContext providerContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(RpcProviderTestFixtures.EchoProvider.class);
        context.refresh();
        return context;
    }

    private RpcProviderMethodRegistry scan(AnnotationConfigApplicationContext context) {
        return new RpcProviderBeanScanner(context, new RpcContractValidator(VALIDATION_UTILS)).scan();
    }

    private RpcProviderLifecycle lifecycle(RpcProviderMethodRegistry methods,
                                           RpcProviderAvailabilityRegistry availability,
                                           RpcProviderServerFactory serverFactory,
                                           EgonRpcProperties properties) {
        return new RpcProviderLifecycle(methods,
                new RpcServerServiceDefinitionFactory(availability), serverFactory,
                availability, List.of(new RpcProviderServerInterceptor()), properties);
    }

    private RpcProviderServerFactory factory(Server server) {
        RpcProviderServerFactory factory = mock(RpcProviderServerFactory.class);
        when(factory.create(anyString(), anyInt(), any(), any())).thenReturn(server);
        return factory;
    }

    private EgonRpcProperties configuredProperties() {
        EgonRpcProperties properties = new EgonRpcProperties();
        properties.getProvider().setEnabled(true);
        properties.getProvider().setBindAddress("127.0.0.1");
        properties.getProvider().setPort(0);
        properties.getProvider().setGracefulShutdownTimeoutMs(100);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private MethodDescriptor<Message, Message> echoMethod() {
        return (MethodDescriptor<Message, Message>) (MethodDescriptor<?, ?>)
                new RpcContractValidator(VALIDATION_UTILS)
                        .validate(RpcProviderTestFixtures.EchoContract.class)
                        .methods().getFirst().grpcMethod();
    }

    private ManagedChannel plaintextChannel(int port) {
        return ManagedChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext().disableServiceConfigLookUp().disableRetry().build();
    }

    private void assertAvailable(RpcProviderMethodRegistry methods,
                                 RpcProviderAvailabilityRegistry availability,
                                 boolean expected) {
        assertThat(methods.providers()).allSatisfy(binding ->
                assertThat(availability.isAvailable(binding.serviceIdentity())).isEqualTo(expected));
    }

    private ServerInterceptor interceptor(String name, List<String> order) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, io.grpc.Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                order.add(name);
                return next.startCall(call, headers);
            }
        };
    }
}
