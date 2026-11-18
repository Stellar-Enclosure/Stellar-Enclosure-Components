package top.egon.cola.component.rpc.consumer.invocation;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.lifecycle.RpcConsumerLifecycleCoordinator;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared blocking/async executor. Each invocation acquires one stable-target lease and dispatches once. */
@Slf4j
public final class RpcInvocationExecutor {

    private final RpcConsumerLifecycleCoordinator lifecycle;
    private final RpcStatusExceptionMapper statusMapper;

    public RpcInvocationExecutor(RpcStatusExceptionMapper statusMapper) { this(null, statusMapper); }

    public RpcInvocationExecutor(RpcConsumerLifecycleCoordinator lifecycle, RpcStatusExceptionMapper statusMapper) {
        this.lifecycle = lifecycle;
        this.statusMapper = statusMapper == null ? new RpcStatusExceptionMapper() : statusMapper;
    }

    public Object executeBlocking(RpcInvocationPlan plan, Object request) {
        CompletableFuture<Object> future = executeAsync(plan, request).toCompletableFuture();
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new EgonRpcException(EgonRpcErrorCode.RPC_CANCELLED, "RPC call was cancelled", exception);
        } catch (CancellationException exception) {
            throw new EgonRpcException(EgonRpcErrorCode.RPC_CANCELLED, "RPC call was cancelled", exception);
        } catch (ExecutionException exception) {
            throw map(unwrap(exception));
        }
    }

    public CompletionStage<Object> executeAsync(RpcInvocationPlan plan, Object request) {
        Invocation invocation = new Invocation(Objects.requireNonNull(plan, "plan"), request);
        invocation.start();
        return invocation.result;
    }

    private final class Invocation {
        private final RpcInvocationPlan plan;
        private final Object request;
        private final RpcInvocationContext context;
        private final ExecutionFuture result = new ExecutionFuture();
        private final AtomicReference<AttemptState> active = new AtomicReference<>();

        private Invocation(RpcInvocationPlan plan, Object request) {
            this.plan = plan;
            this.request = request;
            this.context = new RpcInvocationContext(System.nanoTime(), plan.policy().timeoutMs());
        }

        private void start() {
            RpcChannelLease lease = null;
            try {
                if (lifecycle != null) { lifecycle.requireAccepting(); }
                if (context.expired(System.nanoTime())) { throw deadlineExceeded(); }
                if (!context.beginAttempt()) { return; }
                lease = plan.strategy().acquire();
                lease.beginCall();
                long remaining = context.remainingNanos(System.nanoTime());
                if (remaining == 0) { throw deadlineExceeded(); }
                RpcInvocationPlan.Attempt attempt = plan.invoker().invoke(
                        request, lease, Duration.ofNanos(remaining), context.invocationId());
                if (attempt == null) {
                    throw new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL, "RPC invoker returned no attempt");
                }
                AttemptState state = new AttemptState(lease, attempt);
                active.set(state);
                lease = null; // ownership is now held by AttemptState
                if (context.terminal()) { state.cancel(); return; }
                attempt.completion().toCompletableFuture()
                        .orTimeout(Math.max(1, context.remainingNanos(System.nanoTime())), TimeUnit.NANOSECONDS)
                        .whenComplete((value, error) -> {
                            if (error != null && unwrap(error) instanceof TimeoutException) { state.cancelTransport(); }
                            if (!state.release()) { return; }
                            active.compareAndSet(state, null);
                            if (error != null) { fail(unwrap(error)); }
                            else if (context.expired(System.nanoTime())) { fail(deadlineExceeded()); }
                            else if (value == null || !plan.responseType().isInstance(value)) {
                                fail(new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL, "RPC Provider returned an invalid response type"));
                            } else if (context.terminate()) { result.complete(value); }
                        });
            } catch (Exception exception) {
                if (lease != null) { try { lease.endCall(); } finally { lease.close(); } }
                else {
                    AttemptState state = active.getAndSet(null);
                    if (state != null) { state.cancel(); }
                }
                fail(exception);
            }
        }

        private void fail(Throwable failure) {
            if (context.terminate()) { result.completeExceptionally(map(failure)); }
        }

        private final class ExecutionFuture extends CompletableFuture<Object> {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (!context.terminate()) { return false; }
                AttemptState state = active.getAndSet(null);
                if (state != null) { state.cancel(); }
                return super.cancel(mayInterruptIfRunning);
            }
        }
    }

    private static final class AttemptState {
        private final RpcChannelLease lease;
        private final RpcInvocationPlan.Attempt attempt;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean transportCancelled = new AtomicBoolean();

        private AttemptState(RpcChannelLease lease, RpcInvocationPlan.Attempt attempt) {
            this.lease = lease;
            this.attempt = attempt;
        }

        private boolean release() {
            if (!released.compareAndSet(false, true)) { return false; }
            try { lease.endCall(); } finally { lease.close(); }
            return true;
        }

        private void cancel() { try { cancelTransport(); } finally { release(); } }

        private void cancelTransport() {
            if (!transportCancelled.compareAndSet(false, true)) { return; }
            try { attempt.cancel().run(); }
            catch (RuntimeException exception) {
                log.debug("RPC transport cancellation failed: {}", exception.getClass().getName());
            }
        }
    }

    private EgonRpcException map(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TimeoutException) { return deadlineExceeded(); }
        if (cause instanceof CancellationException) {
            return new EgonRpcException(EgonRpcErrorCode.RPC_CANCELLED, "RPC call was cancelled", cause);
        }
        if (cause instanceof StatusRuntimeException status) { return statusMapper.map(status); }
        if (cause instanceof EgonRpcException rpc) { return rpc; }
        return new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL, "RPC invocation failed", cause);
    }

    private static EgonRpcException deadlineExceeded() {
        return new EgonRpcException(EgonRpcErrorCode.RPC_DEADLINE_EXCEEDED, "RPC deadline exceeded");
    }

    private static Throwable unwrap(Throwable value) {
        Throwable cause = value;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
