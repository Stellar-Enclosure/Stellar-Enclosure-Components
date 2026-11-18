package top.egon.cola.component.rpc.consumer.invocation;

import com.google.protobuf.StringValue;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcInvocationExecutorTest {

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

    private static RpcInvocationExecutor executor() {
        return new RpcInvocationExecutor(new RpcStatusExceptionMapper());
    }
}
