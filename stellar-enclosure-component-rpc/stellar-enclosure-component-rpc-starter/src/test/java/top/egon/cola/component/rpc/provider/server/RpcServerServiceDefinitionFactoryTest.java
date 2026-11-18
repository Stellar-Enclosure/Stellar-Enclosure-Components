package top.egon.cola.component.rpc.provider.server;

import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcService;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.support.RpcProviderTestFixtures;
import top.egon.cola.component.rpc.support.TestGrpcDescriptorFixtures.UnaryFixtureGrpc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class RpcServerServiceDefinitionFactoryTest {

    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());

    private final RpcContractValidator validator = new RpcContractValidator(VALIDATION_UTILS);

    @Test
    void shouldKeepBlockingProviderPath() throws Exception {
        try (RunningServer running = start(
                RpcProviderTestFixtures.EchoContract.class,
                new RpcProviderTestFixtures.EchoProvider(),
                List.of())) {
            Message response = ClientCalls.blockingUnaryCall(
                    running.channel,
                    running.method,
                    CallOptions.DEFAULT,
                    StringValue.of("request")
            );

            assertThat(response).isEqualTo(StringValue.of("provider:request"));
        }
    }

    @Test
    void shouldBridgeAsyncCompletionToGrpcObserver() throws Exception {
        AsyncProvider provider = new AsyncProvider();
        provider.response = CompletableFuture.completedFuture(
                StringValue.of("async-response")
        );
        try (RunningServer running = start(AsyncContract.class, provider, List.of())) {
            Message response = ClientCalls.blockingUnaryCall(
                    running.channel,
                    running.method,
                    CallOptions.DEFAULT,
                    StringValue.of("request")
            );

            assertThat(response).isEqualTo(StringValue.of("async-response"));
        }
    }

    @Test
    void shouldMapAsyncCompletionFailureThroughExistingMapperChain()
            throws Exception {
        AsyncProvider provider = new AsyncProvider();
        provider.response = CompletableFuture.failedFuture(
                new IllegalStateException("provider-secret")
        );
        RpcProviderExceptionMapper mapper = throwable -> Optional.of(
                Status.INVALID_ARGUMENT
                        .withDescription("mapped provider failure")
                        .asRuntimeException()
        );

        try (RunningServer running = start(
                AsyncContract.class,
                provider,
                List.of(mapper))) {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                    running.channel,
                    running.method,
                    CallOptions.DEFAULT,
                    StringValue.of("request")
            ))
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(throwable -> assertThat(
                            ((StatusRuntimeException) throwable)
                                    .getStatus().getCode())
                            .isEqualTo(Status.Code.INVALID_ARGUMENT))
                    .satisfies(throwable -> assertThat(
                            ((StatusRuntimeException) throwable)
                                    .getStatus().getDescription())
                            .isEqualTo("mapped provider failure"));
        }
    }

    @Test
    void shouldRejectNullAsyncStageAndNullAsyncValue() throws Exception {
        AsyncProvider nullStage = new AsyncProvider();
        nullStage.response = null;
        try (RunningServer running = start(
                AsyncContract.class,
                nullStage,
                List.of())) {
            assertInternalFailure(running);
        }

        AsyncProvider nullValue = new AsyncProvider();
        nullValue.response = CompletableFuture.completedFuture(null);
        try (RunningServer running = start(
                AsyncContract.class,
                nullValue,
                List.of())) {
            assertInternalFailure(running);
        }
    }

    @Test
    void shouldCancelFutureWhenGrpcContextIsCancelled() throws Exception {
        CancellableProvider provider = new CancellableProvider();
        try (RunningServer running = start(
                AsyncContract.class,
                provider,
                List.of())) {
            ListenableFuture<Message> call = ClientCalls.futureUnaryCall(
                    running.channel.newCall(running.method, CallOptions.DEFAULT),
                    StringValue.of("request")
            );

            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.cancel(true)).isTrue();
            assertThat(provider.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.isCancelled()).isTrue();
        }
    }

    @Test
    void validatesTheExactPublishedMethodsBeforeAnyListenerCanStart() {
        RpcContractDescriptor contract = validator.validate(AsyncContract.class);
        RpcProviderBinding binding = new RpcProviderBinding(new AsyncProvider(), contract);
        RpcProviderMethodRegistry registry = new RpcProviderMethodRegistry(List.of(binding));
        RpcProviderAdmissionComponent admission = mock(RpcProviderAdmissionComponent.class);
        doThrow(new IllegalStateException("configured admission method was not published"))
                .when(admission).validateMethods(anyCollection());
        RpcServerServiceDefinitionFactory factory = new RpcServerServiceDefinitionFactory(
                new RpcProviderAvailabilityRegistry(), List.of(), admission);

        assertThatThrownBy(() -> factory.create(registry)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not published");
        verify(admission).validateMethods(argThat(methods -> methods.size() == 1
                && methods.iterator().next().method().fullMethodName()
                .equals("egon.rpc.fixture.v1.UnaryFixtureService/Echo")));
        verify(admission, never()).acquire(anyString(), any(Message.class));
    }

    @Test
    void managedBlockingSuccessReleasesItsPermitAfterTheMethodReturns() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        try (RunningServer running = start(RpcProviderTestFixtures.EchoContract.class,
                new RpcProviderTestFixtures.EchoProvider(), List.of(), gate.component)) {
            assertThat(ClientCalls.blockingUnaryCall(running.channel, running.method, CallOptions.DEFAULT,
                    StringValue.of("managed"))).isEqualTo(StringValue.of("provider:managed"));
            assertThat(gate.releases).hasValue(1);
            verify(gate.permits.getFirst(), times(1)).close();
        }
    }

    @Test
    void managedAsyncWorkKeepsItsSlotAfterClientCancellationUntilTheActualSourceCompletes() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        try (ManagedWorkerProvider provider = new ManagedWorkerProvider();
             RunningServer running = start(AsyncContract.class, provider, List.of(), gate.component)) {
            ListenableFuture<Message> first = ClientCalls.futureUnaryCall(
                    running.channel.newCall(running.method, CallOptions.DEFAULT), StringValue.of("first"));
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(true)).isTrue();
            assertThat(provider.transportCancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.source.isCancelled()).isFalse();
            assertThat(provider.finished.getCount()).isEqualTo(1);
            assertThat(gate.releases).hasValue(0);
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(running.channel, running.method,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), StringValue.of("second")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
            assertThat(provider.invocations).hasValue(1);

            provider.allowFinish.countDown();
            assertThat(provider.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.releases).hasValue(1);
            assertThat(ClientCalls.blockingUnaryCall(running.channel, running.method, CallOptions.DEFAULT,
                    StringValue.of("after-finish"))).isEqualTo(StringValue.of("after-release"));
            assertThat(provider.invocations).hasValue(2);
            assertThat(gate.releases).hasValue(2);
            verify(gate.permits.getFirst(), times(1)).close();
        }
    }

    @Test
    void missingManagedAsyncSourceRetainsTheSlotButACompletedNullValueReleasesIt() throws Exception {
        AdmissionGate missingGate = new AdmissionGate();
        AsyncProvider missing = new AsyncProvider();
        try (RunningServer running = start(AsyncContract.class, missing, List.of(), missingGate.component)) {
            assertInternalFailure(running);
            assertThat(missingGate.releases).hasValue(0);
            verify(missingGate.permits.getFirst(), never()).close();
        }
        AdmissionGate completedGate = new AdmissionGate();
        AsyncProvider completed = new AsyncProvider();
        completed.response = CompletableFuture.completedFuture(null);
        try (RunningServer running = start(AsyncContract.class, completed, List.of(), completedGate.component)) {
            assertInternalFailure(running);
            assertThat(completedGate.releases).hasValue(1);
            verify(completedGate.permits.getFirst(), times(1)).close();
        }
    }

    @Test
    void anExternallyCancelledManagedFutureRetainsItsSlotBecauseWorkerTerminationIsUnknown() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        StartedAsyncProvider provider = new StartedAsyncProvider();
        CompletableFuture<StringValue> source = new CompletableFuture<>();
        provider.response = source;
        try (RunningServer running = start(AsyncContract.class, provider, List.of(), gate.component)) {
            ListenableFuture<Message> first = ClientCalls.futureUnaryCall(
                    running.channel.newCall(running.method, CallOptions.DEFAULT), StringValue.of("first"));
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(source.cancel(true)).isTrue();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatusRuntimeException.class);
            assertThat(gate.releases).hasValue(0);
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(running.channel, running.method,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), StringValue.of("second")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
            verify(gate.permits.getFirst(), never()).close();
        }
    }

    @Test
    void onlyTrustedAdmissionMayReturnAuthorityStatusWhileBusinessStatusIsSanitized() throws Exception {
        RpcProviderAdmissionComponent rejecting = mock(RpcProviderAdmissionComponent.class);
        when(rejecting.acquire(anyString(), any(Message.class)))
                .thenThrow(Status.UNAUTHENTICATED.withDescription("verified identity is unavailable").asRuntimeException());
        var untouched = mock(RpcProviderTestFixtures.EchoContract.class);
        try (RunningServer running = start(RpcProviderTestFixtures.EchoContract.class, untouched, List.of(), rejecting)) {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(running.channel, running.method,
                    CallOptions.DEFAULT, StringValue.of("request")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                        assertThat(failure.getStatus().getDescription()).isEqualTo("verified identity is unavailable");
                    });
            verify(untouched, never()).echo(any(StringValue.class));
        }
        AdmissionGate gate = new AdmissionGate();
        var throwingBusiness = mock(RpcProviderTestFixtures.EchoContract.class);
        when(throwingBusiness.echo(any(StringValue.class))).thenThrow(
                Status.PERMISSION_DENIED.withDescription("private-business-payload").asRuntimeException());
        try (RunningServer running = start(RpcProviderTestFixtures.EchoContract.class, throwingBusiness, List.of(), gate.component)) {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(running.channel, running.method,
                    CallOptions.DEFAULT, StringValue.of("request")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                        assertThat(failure.getStatus().getDescription()).isEqualTo("RPC provider invocation failed");
                    });
            assertThat(gate.releases).hasValue(1);
        }
    }

    @Test
    void asyncFailureRestoresOriginalGrpcContextAndTraceWithoutLeakingIntoCompletingThread() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        StartedAsyncProvider provider = new StartedAsyncProvider();
        CompletableFuture<StringValue> source = new CompletableFuture<>();
        provider.response = source;
        AtomicReference<String> callbackLogicalId = new AtomicReference<>();
        AtomicReference<String> callbackTrace = new AtomicReference<>();
        RpcProviderExceptionMapper mapper = failure -> {
            callbackLogicalId.set(RpcInvocationMetadata.current().logicalCallId());
            callbackTrace.set(MDC.get(TraceContext.TRACE_ID));
            return Optional.of(Status.INVALID_ARGUMENT.withDescription("mapped worker failure").asRuntimeException());
        };
        try (RunningServer running = start(AsyncContract.class, provider, List.of(mapper), gate.component)) {
            Metadata headers = new Metadata();
            headers.put(RpcMetadataKeys.LOGICAL_CALL_ID, "factory-logical-call");
            headers.put(RpcMetadataKeys.TRACEPARENT, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            Channel client = ClientInterceptors.intercept(running.channel, MetadataUtils.newAttachHeadersInterceptor(headers));
            ListenableFuture<Message> response = ClientCalls.futureUnaryCall(
                    client.newCall(running.method, CallOptions.DEFAULT), StringValue.of("request"));
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.invocationThread.get()).isSameAs(gate.acquiringThread.get());
            var previousMdc = MDC.getCopyOfContextMap();
            try {
                MDC.put(TraceContext.TRACE_ID, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                source.completeExceptionally(new IllegalStateException("private-worker-details"));
                assertThat(MDC.get(TraceContext.TRACE_ID)).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            } finally {
                if (previousMdc == null) { MDC.clear(); } else { MDC.setContextMap(previousMdc); }
            }
            assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatusRuntimeException.class);
            assertThat(callbackLogicalId).hasValue("factory-logical-call");
            assertThat(callbackTrace).hasValue("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(gate.releases).hasValue(1);
        }
    }

    private void assertInternalFailure(RunningServer running) {
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                running.channel,
                running.method,
                CallOptions.DEFAULT,
                StringValue.of("request")
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(throwable -> assertThat(
                        ((StatusRuntimeException) throwable).getStatus().getCode())
                        .isEqualTo(Status.Code.INTERNAL));
    }

    private RunningServer start(
            Class<?> contractType,
            Object provider,
            List<RpcProviderExceptionMapper> mappers) throws Exception {
        return start(contractType, provider, mappers, null);
    }

    private RunningServer start(Class<?> contractType, Object provider, List<RpcProviderExceptionMapper> mappers,
                                RpcProviderAdmissionComponent admission) throws Exception {
        RpcContractDescriptor contract = validator.validate(contractType);
        RpcProviderBinding binding = new RpcProviderBinding(provider, contract);
        RpcProviderMethodRegistry registry =
                new RpcProviderMethodRegistry(List.of(binding));
        RpcProviderAvailabilityRegistry availability =
                new RpcProviderAvailabilityRegistry();
        availability.available(binding.serviceIdentity());
        Server server = NettyServerBuilder.forPort(0)
                .addService(new RpcServerServiceDefinitionFactory(
                        availability,
                        mappers,
                        admission
                ).create(registry).getFirst())
                .intercept(new RpcProviderServerInterceptor())
                .build()
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress(
                        "127.0.0.1",
                        server.getPort()
                )
                .usePlaintext()
                .build();
        @SuppressWarnings("unchecked")
        MethodDescriptor<Message, Message> method =
                (MethodDescriptor<Message, Message>) (MethodDescriptor<?, ?>)
                        contract.methods().getFirst().grpcMethod();
        return new RunningServer(server, channel, method);
    }

    @EgonRpcService(
            grpcClass = UnaryFixtureGrpc.class,
            group = "test",
            version = "1.0.0"
    )
    interface AsyncContract {

        @EgonRpcMethod(name = "Echo")
        CompletionStage<StringValue> echo(StringValue request);
    }

    /** A deterministic trusted boundary fixture; component policy/identity behavior has separate tests. */
    private static final class AdmissionGate {
        private final RpcProviderAdmissionComponent component = mock(RpcProviderAdmissionComponent.class);
        private final AtomicBoolean occupied = new AtomicBoolean();
        private final AtomicInteger releases = new AtomicInteger();
        private final List<RpcProviderAdmissionComponent.Permit> permits = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<Thread> acquiringThread = new AtomicReference<>();
        private AdmissionGate() {
            when(component.acquire(anyString(), any(Message.class))).thenAnswer(ignored -> {
                acquiringThread.set(Thread.currentThread());
                if (!occupied.compareAndSet(false, true)) {
                    throw Status.RESOURCE_EXHAUSTED.withDescription("provider concurrent work capacity exceeded").asRuntimeException();
                }
                RpcProviderAdmissionComponent.Permit permit = mock(RpcProviderAdmissionComponent.Permit.class);
                AtomicBoolean closed = new AtomicBoolean();
                doAnswer(call -> {
                    if (closed.compareAndSet(false, true)) { occupied.set(false); releases.incrementAndGet(); }
                    return null;
                }).when(permit).close();
                permits.add(permit);
                return permit;
            });
        }
    }

    private static final class ManagedWorkerProvider implements AsyncContract, AutoCloseable {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<StringValue> source = new CompletableFuture<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch transportCancelled = new CountDownLatch(1);
        private final CountDownLatch allowFinish = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        @Override public CompletionStage<StringValue> echo(StringValue request) {
            if (invocations.incrementAndGet() > 1) { return CompletableFuture.completedFuture(StringValue.of("after-release")); }
            Context context = Context.current();
            context.addListener(ignored -> transportCancelled.countDown(), Runnable::run);
            worker.execute(() -> {
                started.countDown();
                try {
                    allowFinish.await();
                    source.complete(StringValue.of("actual-work-finished"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    source.completeExceptionally(interrupted);
                } finally { finished.countDown(); }
            });
            return source;
        }
        @Override public void close() throws Exception {
            allowFinish.countDown();
            worker.shutdown();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static final class StartedAsyncProvider extends AsyncProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<Thread> invocationThread = new AtomicReference<>();
        @Override public CompletionStage<StringValue> echo(StringValue request) {
            invocationThread.set(Thread.currentThread());
            started.countDown();
            return super.echo(request);
        }
    }

    private static class AsyncProvider implements AsyncContract {

        protected CompletionStage<StringValue> response;

        @Override
        public CompletionStage<StringValue> echo(StringValue request) {
            return response;
        }
    }

    private static final class CancellableProvider extends AsyncProvider {

        private final CountDownLatch started = new CountDownLatch(1);

        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public CompletionStage<StringValue> echo(StringValue request) {
            CancellableFuture future = new CancellableFuture(cancelled);
            started.countDown();
            return future;
        }
    }

    private static final class CancellableFuture
            extends CompletableFuture<StringValue> {

        private final CountDownLatch cancelled;

        private CancellableFuture(CountDownLatch cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = super.cancel(mayInterruptIfRunning);
            if (result) {
                cancelled.countDown();
            }
            return result;
        }
    }

    private static final class RunningServer implements AutoCloseable {

        private final Server server;

        private final ManagedChannel channel;

        private final MethodDescriptor<Message, Message> method;

        private RunningServer(
                Server server,
                ManagedChannel channel,
                MethodDescriptor<Message, Message> method) {
            this.server = server;
            this.channel = channel;
            this.method = method;
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
