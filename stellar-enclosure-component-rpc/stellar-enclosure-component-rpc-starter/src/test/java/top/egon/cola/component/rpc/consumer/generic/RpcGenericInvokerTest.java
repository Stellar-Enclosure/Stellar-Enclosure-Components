package top.egon.cola.component.rpc.consumer.generic;

import com.google.protobuf.StringValue;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInterceptorFactory;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationPlan;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;
import top.egon.cola.component.rpc.support.RpcProviderTestFixtures;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcGenericInvokerTest {
    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
    private static final String SERVICE = "egon.rpc.fixture.v1.UnaryFixtureService";
    private static final String METHOD = SERVICE + "/Echo";

    @Test
    void invokesCanonicalRawBytesThroughTheNativeUnaryServer() throws Exception {
        try (RunningServer running = server()) {
            byte[] response = running.invoker.invokeBlocking(invocation(StringValue.of("raw"), 2000));
            assertThat(StringValue.parseFrom(response)).isEqualTo(StringValue.of("provider:raw"));
        }
    }

    @Test
    void invokesAsyncAndReturnsTheSameSerializedResponse() throws Exception {
        try (RunningServer running = server()) {
            CompletionStage<byte[]> response = running.invoker.invokeAsync(invocation(StringValue.of("async"), 2000));
            assertThat(StringValue.parseFrom(response.toCompletableFuture().get(5, TimeUnit.SECONDS)))
                    .isEqualTo(StringValue.of("provider:async"));
        }
    }

    @Test
    void rejectsProxyControlMetadataAddedByAnExtensionBeforeTransport() throws Exception {
        Metadata disallowed = new Metadata();
        disallowed.put(Metadata.Key.of("x-envoy-retry-on", Metadata.ASCII_STRING_MARSHALLER), "unavailable");
        RpcClientInterceptorFactory extension = invocation -> MetadataUtils.newAttachHeadersInterceptor(disallowed);

        try (RunningServer running = server(List.of(extension))) {
            assertThatThrownBy(() -> running.invoker.invokeBlocking(invocation(StringValue.of("blocked"), 2000)))
                    .isInstanceOf(EgonRpcException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnapprovedBindingOrMethodBeforeDispatch() {
        RpcInvocationExecutor executor = mock(RpcInvocationExecutor.class);
        try (RpcGenericTargetCache cache = cache(new AtomicInteger())) {
            RpcGenericInvoker invoker = new RpcGenericInvoker(cache, executor, identity(), List.of());
            assertThatThrownBy(() -> invoker.invokeBlocking(new RpcGenericInvocation("unknown-binding", SERVICE,
                    "test", "1.0.0", METHOD, new byte[0], 1000))).isInstanceOf(EgonRpcException.class);
            assertThatThrownBy(() -> invoker.invokeBlocking(new RpcGenericInvocation("sample-binding", SERVICE,
                    "test", "1.0.0", SERVICE + "/Delete", new byte[0], 1000)))
                    .isInstanceOf(EgonRpcException.class).hasMessageContaining("not approved");
            org.mockito.Mockito.verifyNoInteractions(executor);
        }
    }

    @Test
    void rejectsNonCanonicalMethodsAndExposesNoCallerNetworkOrFallbackSurface() {
        assertThatThrownBy(() -> new RpcGenericInvocation("sample-binding", SERVICE, "test", "1.0.0",
                SERVICE + ".Echo", new byte[0], 1000)).isInstanceOf(EgonRpcException.class);
        assertThatThrownBy(() -> new RpcGenericInvocation("sample-binding", SERVICE, "test", "1.0.0",
                "other.Service/Echo", new byte[0], 1000)).isInstanceOf(EgonRpcException.class);
        assertThatThrownBy(() -> new RpcGenericInvocation("${binding}", SERVICE, "test", "1.0.0",
                METHOD, new byte[0], 1000)).isInstanceOf(EgonRpcException.class);
        assertThat(RpcGenericInvocation.class.getDeclaredFields()).noneMatch(field -> Set.of(
                "metadata", "authorization", "host", "port", "mode", "fallback", "retries", "loadbalance"
        ).contains(field.getName().toLowerCase()));
    }

    @Test
    void forwardsAsyncCancellationAndReleasesTheCachedEntry() {
        RpcInvocationExecutor executor = mock(RpcInvocationExecutor.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        when(executor.executeAsync(any(RpcInvocationPlan.class), any())).thenReturn(pending);
        AtomicInteger closes = new AtomicInteger();
        RpcGenericTargetCache cache = cache(closes);
        RpcGenericInvoker invoker = new RpcGenericInvoker(cache, executor, identity(), List.of());

        CompletableFuture<byte[]> response = invoker.invokeAsync(invocation(StringValue.of("cancel"), 1000)).toCompletableFuture();
        assertThat(response.cancel(true)).isTrue();
        assertThat(pending.isCancelled()).isTrue();
        cache.close();
        assertThat(closes).hasValue(1);
    }

    @Test
    void capsRequestTimeoutAndDoesNotChangeTheStableTargetOrMethod() {
        RpcInvocationExecutor executor = mock(RpcInvocationExecutor.class);
        when(executor.executeBlocking(any(RpcInvocationPlan.class), any())).thenReturn(new byte[]{7});
        try (RpcGenericTargetCache cache = cache(new AtomicInteger())) {
            RpcGenericInvoker invoker = new RpcGenericInvoker(cache, executor, identity(), List.of());
            assertThat(invoker.invokeBlocking(invocation(StringValue.of("cap"), 6000))).containsExactly((byte) 7);
            ArgumentCaptor<RpcInvocationPlan> plans = ArgumentCaptor.forClass(RpcInvocationPlan.class);
            verify(executor).executeBlocking(plans.capture(), any());
            assertThat(plans.getValue().policy().timeoutMs()).isEqualTo(2000);
            assertThat(plans.getValue().fullMethodName()).isEqualTo(METHOD);
            assertThat(plans.getValue().strategy().target().dnsTarget())
                    .isEqualTo("dns:///sample-rpc.apps-prod.svc.cluster.local:18122");
        }
    }

    @Test
    void propagatesAvailabilityFailureWithoutNullSuccessOrFallback() {
        RpcInvocationExecutor executor = mock(RpcInvocationExecutor.class);
        when(executor.executeBlocking(any(RpcInvocationPlan.class), any())).thenThrow(
                new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE, "stable target unavailable"));
        try (RpcGenericTargetCache cache = cache(new AtomicInteger())) {
            RpcGenericInvoker invoker = new RpcGenericInvoker(cache, executor, identity(), List.of());
            assertThatThrownBy(() -> invoker.invokeBlocking(invocation(StringValue.of("missing"), 1000)))
                    .isInstanceOfSatisfying(EgonRpcException.class, exception ->
                            assertThat(exception.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE));
        }
    }

    @Test
    void keepsCommandAndResponsePayloadsDefensive() {
        byte[] original = new byte[]{1, 2};
        RpcGenericInvocation command = new RpcGenericInvocation("sample-binding", SERVICE, "test", "1.0.0", METHOD, original, 1000);
        original[0] = 9;
        byte[] read = command.requestPayload();
        read[1] = 8;
        assertThat(command.requestPayload()).containsExactly(1, 2);
        RpcInvocationExecutor executor = mock(RpcInvocationExecutor.class);
        byte[] provider = new byte[]{3};
        when(executor.executeBlocking(any(RpcInvocationPlan.class), any())).thenReturn(provider);
        try (RpcGenericTargetCache cache = cache(new AtomicInteger())) {
            byte[] response = new RpcGenericInvoker(cache, executor, identity(), List.of()).invokeBlocking(command);
            provider[0] = 4;
            assertThat(response).containsExactly(3);
        }
    }

    private RpcGenericInvocation invocation(StringValue request, long timeoutMs) {
        return new RpcGenericInvocation("sample-binding", SERVICE, "test", "1.0.0", METHOD, request.toByteArray(), timeoutMs);
    }

    private RpcReferenceDefinitionResolver resolver() {
        EgonRpcProperties properties = new EgonRpcProperties();
        properties.getConsumer().setDefaultTimeoutMs(2000).getBindings().put("sample-binding",
                new EgonRpcProperties.RpcServiceBindingConfig().setVersion(1).setNamespace("apps-prod")
                        .setService("sample-rpc").setPort(18122).setMethods(Set.of(METHOD)));
        return new RpcReferenceDefinitionResolver(properties);
    }

    private RpcGenericTargetCache cache(AtomicInteger closes) {
        return new RpcGenericTargetCache(resolver(), definition -> strategy(definition.target(), closes),
                4, Duration.ofSeconds(1), System::currentTimeMillis);
    }

    private RpcReferenceStrategy strategy(RpcServiceTargetVO target, AtomicInteger closes) {
        RpcReferenceStrategy strategy = mock(RpcReferenceStrategy.class);
        when(strategy.target()).thenReturn(target);
        org.mockito.Mockito.doAnswer(ignored -> { closes.incrementAndGet(); return null; }).when(strategy).close();
        return strategy;
    }

    private RunningServer server() throws Exception {
        return server(List.of());
    }

    private RunningServer server(List<RpcClientInterceptorFactory> extensions) throws Exception {
        RpcContractDescriptor contract = new RpcContractValidator(VALIDATION_UTILS).validate(RpcProviderTestFixtures.EchoContract.class);
        RpcProviderBinding binding = new RpcProviderBinding(new RpcProviderTestFixtures.EchoProvider(), contract);
        RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
        availability.available(binding.serviceIdentity());
        Server server = NettyServerBuilder.forPort(0)
                .addService(new RpcServerServiceDefinitionFactory(availability)
                        .create(new RpcProviderMethodRegistry(List.of(binding))).getFirst()).build().start();
        // A local channel is injected by the test; production targets remain configured Service DNS names.
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition -> {
            RpcReferenceStrategy strategy = strategy(definition.target(), new AtomicInteger());
            when(strategy.acquire()).thenAnswer(ignored -> {
                RpcChannelLease lease = mock(RpcChannelLease.class);
                when(lease.channel()).thenReturn(channel);
                return lease;
            });
            return strategy;
        }, 4, Duration.ofSeconds(1), System::currentTimeMillis);
        RpcInvocationExecutor executor = new RpcInvocationExecutor(new RpcStatusExceptionMapper());
        return new RunningServer(server, channel, cache, executor,
                new RpcGenericInvoker(cache, executor, identity(), extensions));
    }

    private RpcProcessIdentity identity() {
        return new RpcProcessIdentity("generic-test", "test", "127.0.0.1", 1, "generic-1");
    }

    private record RunningServer(Server server, ManagedChannel channel, RpcGenericTargetCache cache,
                                 RpcInvocationExecutor executor, RpcGenericInvoker invoker) implements AutoCloseable {
        @Override public void close() throws Exception {
            executor.close();
            cache.close();
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
