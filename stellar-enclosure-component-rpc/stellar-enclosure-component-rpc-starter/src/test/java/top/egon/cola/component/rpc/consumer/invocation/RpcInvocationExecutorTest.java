package top.egon.cola.component.rpc.consumer.invocation;

import com.google.protobuf.StringValue;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.invocation.RpcFailureStage;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcInvocationExecutorTest {
    private final List<RpcInvocationExecutor> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(RpcInvocationExecutor::close);
    }


    @Test
    void incomingGrpcDeadlineCapsTheFirstAttemptWithoutResettingTheClock() throws Exception {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicLong attemptMillis = new AtomicLong();
        RpcInvocationPlan plan = plan(strategy, 1500, (request, ignored, timeout, invocationId) -> {
            attemptMillis.set(timeout.toMillis());
            return new RpcInvocationPlan.Attempt(
                    CompletableFuture.completedFuture(StringValue.of("response")), null);
        });
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            Context.CancellableContext incoming = Context.current().withDeadlineAfter(500, TimeUnit.MILLISECONDS, timer);
            try {
                assertThat(incoming.call(() -> executor().executeBlocking(plan, StringValue.of("request"))))
                        .isEqualTo(StringValue.of("response"));
            } finally {
                incoming.cancel(null);
            }
        }
        assertThat(attemptMillis).hasValueBetween(1, 500);
    }

    @Test
    void unavailableIsTerminalAfterOneAttemptToTheStableTarget() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, invocationId) -> {
            attempts.incrementAndGet();
            return new RpcInvocationPlan.Attempt(
                    CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), () -> { });
        });

        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE));
        assertThat(attempts).hasValue(1);
        verify(strategy, times(1)).acquire();
        verify(lease, times(1)).beginCall();
        verify(lease, times(1)).endCall();
        verify(lease, times(1)).close();
    }

    @Test
    void asyncCancellationCancelsTransportAndReleasesExactlyOnce() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        AtomicInteger cancels = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, invocationId) ->
                new RpcInvocationPlan.Attempt(pending, cancels::incrementAndGet));

        CompletableFuture<Object> result = executor().executeAsync(plan, StringValue.of("request"))
                .toCompletableFuture();
        assertThat(result.cancel(true)).isTrue();
        pending.complete(StringValue.of("late"));
        assertThat(result.isCancelled()).isTrue();
        assertThat(cancels).hasValue(1);
        verify(lease, times(1)).endCall();
        verify(lease, times(1)).close();
    }

    @Test
    void deadlineCancelsPendingTransportWithoutAnotherAttempt() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger cancels = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 100, (request, ignored, timeout, invocationId) ->
                new RpcInvocationPlan.Attempt(new CompletableFuture<>(), cancels::incrementAndGet));

        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_DEADLINE_EXCEEDED));
        assertThat(cancels).hasValue(1);
        verify(strategy, times(1)).acquire();
        verify(lease, times(1)).close();
    }

    @Test
    void invalidResponseFailsClosedAndReleasesLease() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, invocationId) ->
                new RpcInvocationPlan.Attempt(CompletableFuture.completedFuture(null), null));
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_INTERNAL));
        verify(lease).close();
    }

    @Test
    void completedSuccessReleasesTheLeaseWithoutCancellingTransport() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger cancels = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, invocationId) ->
                new RpcInvocationPlan.Attempt(CompletableFuture.completedFuture(StringValue.of("response")),
                        cancels::incrementAndGet));

        assertThat(executor().executeBlocking(plan, StringValue.of("request")))
                .isEqualTo(StringValue.of("response"));
        assertThat(cancels).hasValue(0);
        verify(lease).beginCall();
        verify(lease).endCall();
        verify(lease).close();
    }

    @Test
    void synchronousDispatchFailureReleasesTheLeaseAndDoesNotRetry() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, invocationId) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("dispatch failed");
        });

        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_INTERNAL));
        assertThat(attempts).hasValue(1);
        verify(strategy).acquire();
        verify(lease).endCall();
        verify(lease).close();
    }

    @Test
    void approvedReadRetriesOnceOnTheSameServiceWithOneIdAndCapturedCallerState() throws Exception {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease first = mock(RpcChannelLease.class);
        RpcChannelLease second = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(first, second);
        List<String> logicalIds = new CopyOnWriteArrayList<>();
        List<Integer> numbers = new CopyOnWriteArrayList<>();
        List<Long> budgets = new CopyOnWriteArrayList<>();
        List<String> traceIds = new CopyOnWriteArrayList<>();
        List<String> tags = new CopyOnWriteArrayList<>();
        Context.Key<String> key = Context.key("rpc-test-caller-state");
        List<String> contexts = new CopyOnWriteArrayList<>();
        AtomicLong firstFailed = new AtomicLong();
        AtomicLong retryDispatched = new AtomicLong();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                (request, lease, timeout, attempt) -> {
                    logicalIds.add(attempt.logicalCallId());
                    numbers.add(attempt.number());
                    budgets.add(timeout.toNanos());
                    traceIds.add(TraceContext.currentOrCreate().traceId());
                    tags.add(MDC.get("rpc-test-tag"));
                    contexts.add(key.get());
                    if (attempt.number() == 1) {
                        firstFailed.set(System.nanoTime());
                        return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                    }
                    retryDispatched.set(System.nanoTime());
                    return new RpcInvocationPlan.Attempt(CompletableFuture.completedFuture(StringValue.of("retried")), null);
                });
        var previousMdc = MDC.getCopyOfContextMap();
        try {
            MDC.put("rpc-test-tag", "caller-tag");
            assertThat(Context.current().withValue(key, "caller-context")
                    .call(() -> executor().executeBlocking(plan, StringValue.of("request"))))
                    .isEqualTo(StringValue.of("retried"));
        } finally {
            if (previousMdc == null) { MDC.clear(); } else { MDC.setContextMap(previousMdc); }
        }
        assertThat(logicalIds).hasSize(2);
        assertThat(logicalIds.getFirst()).isEqualTo(logicalIds.getLast());
        assertThat(numbers).containsExactly(1, 2);
        assertThat(budgets).allSatisfy(value -> assertThat(value).isBetween(1L, TimeUnit.MILLISECONDS.toNanos(600)));
        assertThat(traceIds).hasSize(2);
        assertThat(traceIds.getFirst()).isEqualTo(traceIds.getLast());
        assertThat(tags).containsExactly("caller-tag", "caller-tag");
        assertThat(contexts).containsExactly("caller-context", "caller-context");
        assertThat(retryDispatched.get() - firstFailed.get()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(25));
        verify(strategy, times(2)).acquire();
        verify(first).endCall();
        verify(first).close();
        verify(second).endCall();
        verify(second).close();
    }

    @Test
    void twoUnavailableFailuresExhaustTheSingleApprovedReplay() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease first = mock(RpcChannelLease.class);
        RpcChannelLease second = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(first, second);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                (request, lease, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE));
        assertThat(attempts).hasValue(2);
        verify(strategy, times(2)).acquire();
    }

    @Test
    void retryApprovalCannotBeAppliedToAnotherExactMethod() {
        assertThatThrownBy(() -> new RpcInvocationPlan("sample.Service", "sample.Service/Echo",
                new RpcReferencePolicy(1500, 1, 600), strategy(), StringValue.class,
                (request, lease, timeout, attempt) -> new RpcInvocationPlan.Attempt(new CompletableFuture<>(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact method");
    }

    @Test
    void authQuotaBusinessCancellationAndDeadlineFailuresNeverReplay() {
        Metadata quota = new Metadata();
        RpcFailureStage.PROVIDER.put(quota);
        quota.put(RpcMetadataKeys.ERROR_TYPE, "rate-limit");
        Metadata quotaStore = new Metadata();
        RpcFailureStage.PROVIDER.put(quotaStore);
        quotaStore.put(RpcMetadataKeys.ERROR_TYPE, RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE);
        Metadata authority = new Metadata();
        RpcFailureStage.PROVIDER.put(authority);
        authority.put(RpcMetadataKeys.ERROR_TYPE, "authentication");
        List<RuntimeException> failures = List.of(Status.PERMISSION_DENIED.asRuntimeException(),
                Status.UNAUTHENTICATED.asRuntimeException(), Status.RESOURCE_EXHAUSTED.asRuntimeException(),
                Status.FAILED_PRECONDITION.asRuntimeException(), Status.CANCELLED.asRuntimeException(),
                Status.DEADLINE_EXCEEDED.asRuntimeException(), Status.UNAVAILABLE.asRuntimeException(quota),
                Status.UNAVAILABLE.asRuntimeException(quotaStore), Status.UNAVAILABLE.asRuntimeException(authority));
        for (RuntimeException failure : failures) {
            RpcReferenceStrategy strategy = approvedStrategy();
            RpcChannelLease lease = mock(RpcChannelLease.class);
            when(strategy.acquire()).thenReturn(lease);
            AtomicInteger attempts = new AtomicInteger();
            RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                    (request, ignored, timeout, attempt) -> {
                        attempts.incrementAndGet();
                        return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(failure), null);
                    });
            assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                    .isInstanceOf(EgonRpcException.class);
            assertThat(attempts).hasValue(1);
            verify(strategy).acquire();
            verify(lease).close();
        }
    }

    @Test
    void responseHeadersCommitTheAttemptEvenWhenItLaterBecomesUnavailable() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                (request, ignored, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    attempt.markResponseCommitted();
                    return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOf(EgonRpcException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void synchronousUnavailableSetupFailureDoesNotQualifyForReplay() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                (request, ignored, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    throw Status.UNAVAILABLE.asRuntimeException();
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOf(EgonRpcException.class);
        assertThat(attempts).hasValue(1);
        verify(lease).close();
    }

    @Test
    void perAttemptDeadlineStopsTheWholeLogicalCallWithoutReplay() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 100),
                (request, ignored, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    return new RpcInvocationPlan.Attempt(new CompletableFuture<>(), cancels::incrementAndGet);
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOfSatisfying(EgonRpcException.class, error ->
                        assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_DEADLINE_EXCEEDED));
        assertThat(attempts).hasValue(1);
        assertThat(cancels).hasValue(1);
        verify(lease).close();
    }

    @Test
    void insufficientRemainingBudgetDoesNotStartTheBackoffOrAnotherStream() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(20, 1, 20),
                (request, ignored, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOf(EgonRpcException.class);
        assertThat(attempts).hasValue(1);
        verify(strategy).acquire();
    }

    @Test
    void callerCancellationDuringBackoffPreventsTheSecondAttempt() {
        RpcReferenceStrategy strategy = approvedStrategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger attempts = new AtomicInteger();
        RpcInvocationPlan plan = approvedPlan(strategy, new RpcReferencePolicy(1500, 1, 600),
                (request, ignored, timeout, attempt) -> {
                    attempts.incrementAndGet();
                    return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                });
        Context.CancellableContext caller = Context.current().withCancellation();
        AtomicReference<CompletableFuture<Object>> response = new AtomicReference<>();
        caller.run(() -> response.set(executor().executeAsync(plan, StringValue.of("request")).toCompletableFuture()));
        caller.cancel(null);
        assertThatThrownBy(() -> response.get().join()).hasCauseInstanceOf(EgonRpcException.class);
        assertThat(attempts).hasValue(1);
        verify(strategy).acquire();
        verify(lease).close();
    }

    @Test
    void closingExecutorCancelsPendingTransportAndRejectsNewCalls() {
        RpcReferenceStrategy strategy = strategy();
        RpcChannelLease lease = mock(RpcChannelLease.class);
        when(strategy.acquire()).thenReturn(lease);
        AtomicInteger cancels = new AtomicInteger();
        RpcInvocationPlan plan = plan(strategy, 1000, (request, ignored, timeout, attempt) ->
                new RpcInvocationPlan.Attempt(new CompletableFuture<>(), cancels::incrementAndGet));
        RpcInvocationExecutor executor = executor();
        CompletableFuture<Object> result = executor.executeAsync(plan, StringValue.of("request")).toCompletableFuture();
        executor.close();
        assertThat(result.isCancelled()).isTrue();
        assertThat(cancels).hasValue(1);
        verify(lease).close();
        assertThatThrownBy(() -> executor.executeAsync(plan, StringValue.of("request")))
                .isInstanceOf(EgonRpcException.class);
    }

    @Test
    void preparesAuthenticationOnceOnOriginalThreadBeforeEitherTransportAttempt() {
        RpcReferenceStrategy strategy = approvedStrategy();
        when(strategy.acquire()).thenReturn(mock(RpcChannelLease.class), mock(RpcChannelLease.class));
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Thread> preparationThread = new AtomicReference<>();
        AtomicReference<String> preparedId = new AtomicReference<>();
        Thread originalThread = Thread.currentThread();
        ThreadLocal<String> callerIdentity = new ThreadLocal<>();
        callerIdentity.set("verified-caller");
        RpcInvocationPlan plan = new RpcInvocationPlan("egon.tianquan.shoubing.v1.IdentityDirectoryService",
                RpcReferencePolicy.APPROVED_READ_METHOD, new RpcReferencePolicy(1500, 1, 600), strategy, StringValue.class,
                (request, logicalCallId) -> {
                    preparations.incrementAndGet();
                    preparationThread.set(Thread.currentThread());
                    preparedId.set(logicalCallId);
                    String frozenIdentity = callerIdentity.get();
                    assertThat(Context.current().getDeadline()).isNotNull();
                    assertThat(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS)).isBetween(1L, 1500L);
                    return (payload, lease, timeout, attempt) -> {
                        assertThat(frozenIdentity).isEqualTo("verified-caller");
                        assertThat(attempt.logicalCallId()).isEqualTo(preparedId.get());
                        if (attempts.incrementAndGet() == 1) {
                            return new RpcInvocationPlan.Attempt(CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()), null);
                        }
                        return new RpcInvocationPlan.Attempt(CompletableFuture.completedFuture(StringValue.of("prepared")), null);
                    };
                });
        try {
            assertThat(executor().executeBlocking(plan, StringValue.of("request"))).isEqualTo(StringValue.of("prepared"));
        } finally {
            callerIdentity.remove();
        }
        assertThat(preparations).hasValue(1);
        assertThat(attempts).hasValue(2);
        assertThat(preparationThread).hasValue(originalThread);
    }

    @Test
    void preparationFailureIsTerminalBeforeLeaseAcquisitionEvenForApprovedRead() {
        RpcReferenceStrategy strategy = approvedStrategy();
        AtomicInteger preparations = new AtomicInteger();
        RpcInvocationPlan plan = new RpcInvocationPlan("egon.tianquan.shoubing.v1.IdentityDirectoryService",
                RpcReferencePolicy.APPROVED_READ_METHOD, new RpcReferencePolicy(1500, 1, 600), strategy, StringValue.class,
                (request, logicalCallId) -> {
                    preparations.incrementAndGet();
                    throw Status.UNAVAILABLE.asRuntimeException();
                });
        assertThatThrownBy(() -> executor().executeBlocking(plan, StringValue.of("request")))
                .isInstanceOf(EgonRpcException.class);
        assertThat(preparations).hasValue(1);
        verify(strategy, org.mockito.Mockito.never()).acquire();
    }

    private static RpcReferenceStrategy approvedStrategy() {
        RpcReferenceStrategy strategy = mock(RpcReferenceStrategy.class);
        when(strategy.target()).thenReturn(new RpcServiceTargetVO("approved-binding", 1, "permission-prod", "identity-directory-rpc", 18122,
                Set.of(RpcReferencePolicy.APPROVED_READ_METHOD)));
        return strategy;
    }

    private static RpcInvocationPlan approvedPlan(RpcReferenceStrategy strategy, RpcReferencePolicy policy,
                                                 RpcInvocationPlan.UnaryInvoker invoker) {
        return new RpcInvocationPlan("egon.tianquan.shoubing.v1.IdentityDirectoryService", RpcReferencePolicy.APPROVED_READ_METHOD,
                policy, strategy, StringValue.class, invoker);
    }

    private static RpcReferenceStrategy strategy() {
        RpcReferenceStrategy strategy = mock(RpcReferenceStrategy.class);
        when(strategy.target()).thenReturn(new RpcServiceTargetVO(
                "sample-binding", 1, "apps-prod", "sample-rpc", 18122, Set.of("sample.Service/Echo")));
        return strategy;
    }

    private static RpcInvocationPlan plan(RpcReferenceStrategy strategy, long timeout,
                                           RpcInvocationPlan.UnaryInvoker invoker) {
        return new RpcInvocationPlan("sample.Service", "sample.Service/Echo", new RpcReferencePolicy(timeout),
                strategy, StringValue.class, invoker);
    }

    private RpcInvocationExecutor executor() {
        RpcInvocationExecutor executor = new RpcInvocationExecutor(new RpcStatusExceptionMapper());
        executors.add(executor);
        return executor;
    }
}
