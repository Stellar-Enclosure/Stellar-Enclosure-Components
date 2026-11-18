package top.egon.cola.component.rpc.consumer.invocation;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.lifecycle.RpcConsumerLifecycleCoordinator;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the sole bounded retry layer, one absolute deadline and cancellation for a logical unary call. */
@Slf4j
public final class RpcInvocationExecutor implements AutoCloseable {
    private final RpcConsumerLifecycleCoordinator lifecycle;
    private final RpcStatusExceptionMapper statusMapper;
    private final Object monitor = new Object();
    private final Set<Invocation> invocations = ConcurrentHashMap.newKeySet();
    private ScheduledThreadPoolExecutor scheduler;
    private boolean closed;

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
        synchronized (monitor) {
            if (closed) { throw unavailable(); }
            invocations.add(invocation);
        }
        invocation.start();
        return invocation.result;
    }

    private ScheduledThreadPoolExecutor timer() {
        synchronized (monitor) {
            if (closed) { throw unavailable(); }
            if (scheduler == null) {
                scheduler = new ScheduledThreadPoolExecutor(1, task -> {
                    Thread thread = new Thread(task, "egon-rpc-invocation-timer");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.setRemoveOnCancelPolicy(true);
                scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            }
            return scheduler;
        }
    }

    @Override
    public void close() {
        ArrayList<Invocation> closing;
        ScheduledThreadPoolExecutor stopping;
        synchronized (monitor) {
            if (closed) { return; }
            closed = true;
            closing = new ArrayList<>(invocations);
            stopping = scheduler;
        }
        closing.forEach(invocation -> invocation.result.cancel(true));
        if (stopping != null) { stopping.shutdownNow(); }
    }

    private final class Invocation {
        private final Object gate = new Object();
        private final RpcInvocationPlan plan;
        private final Object request;
        private final Context caller = Context.current();
        private final TraceContext trace = TraceContext.currentOrCreate();
        private final Deadline totalDeadline;
        private final RpcInvocationContext context;
        private final ExecutionFuture result = new ExecutionFuture();
        private final AtomicReference<AttemptState> active = new AtomicReference<>();
        private final Context.CancellationListener callerCancellation = ignored -> cancelledByCaller();
        private Context.CancellableContext logicalCaller;
        private RpcInvocationPlan.UnaryInvoker preparedInvoker;
        private ScheduledFuture<?> backoff;

        private Invocation(RpcInvocationPlan plan, Object request) {
            this.plan = plan;
            this.request = request;
            long ceiling = plan.policy().timeoutMs();
            if (approvedMethod()) { ceiling = Math.min(ceiling, RpcReferencePolicy.MAX_APPROVED_TOTAL_TIMEOUT_MS); }
            Deadline local = Deadline.after(ceiling, TimeUnit.MILLISECONDS);
            this.totalDeadline = caller.getDeadline() == null ? local : local.minimum(caller.getDeadline());
            this.context = new RpcInvocationContext(System.nanoTime(),
                    Duration.ofNanos(Math.max(0, totalDeadline.timeRemaining(TimeUnit.NANOSECONDS))),
                    1 + plan.policy().retries());
            result.whenComplete((value, error) -> cleanup());
        }

        private boolean approvedMethod() { return RpcReferencePolicy.APPROVED_READ_METHOD.equals(plan.fullMethodName()); }

        private void start() {
            synchronized (gate) {
                if (context.terminal()) { return; }
                try {
                    if (lifecycle != null) { lifecycle.requireAccepting(); }
                    if (caller.isCancelled()) { cancelledByCaller(); return; }
                    if (remainingNanos() <= 0) { finishFailure(deadlineExceeded()); return; }
                    logicalCaller = caller.withDeadline(totalDeadline, timer());
                    logicalCaller.addListener(callerCancellation, Runnable::run);
                } catch (RuntimeException exception) {
                    finishFailure(exception);
                    return;
                }
            }
            runCaptured(() -> {
                synchronized (gate) {
                    if (context.terminal()) { return; }
                }
                RpcInvocationPlan.UnaryInvoker prepared;
                try {
                    // Prepare identity/interceptors once on the original calling thread.
                    // The deadline listener can terminate the call while preparation is in progress.
                    prepared = plan.prepare(request, context.logicalCallId());
                } catch (RuntimeException exception) {
                    finishFailure(exception);
                    return;
                }
                synchronized (gate) {
                    if (context.terminal()) { return; }
                    preparedInvoker = prepared;
                }
                startAttempt();
            });
        }

        private long remainingNanos() {
            return Math.max(0, Math.min(context.remainingNanos(System.nanoTime()),
                    totalDeadline.timeRemaining(TimeUnit.NANOSECONDS)));
        }

        private void startAttempt() {
            AttemptState state;
            synchronized (gate) {
                if (context.terminal()) { return; }
                backoff = null;
                if (caller.isCancelled()) { cancelledByCaller(); return; }
                if (remainingNanos() <= 0) { finishFailure(deadlineExceeded()); return; }
                if (!context.beginAttempt()) { return; }
                RpcChannelLease lease = null;
                RpcInvocationPlan.Attempt attempt = null;
                try {
                    if (lifecycle != null) { lifecycle.requireAccepting(); }
                    lease = Objects.requireNonNull(plan.strategy().acquire(), "RPC channel lease");
                    lease.beginCall();
                    long attemptLimit = plan.policy().attemptTimeoutMs();
                    if (approvedMethod()) { attemptLimit = Math.min(attemptLimit, RpcReferencePolicy.MAX_APPROVED_ATTEMPT_TIMEOUT_MS); }
                    Deadline deadline = Deadline.after(attemptLimit, TimeUnit.MILLISECONDS).minimum(totalDeadline);
                    RpcInvocationPlan.AttemptContextVO attemptContext = new RpcInvocationPlan.AttemptContextVO(
                            context.logicalCallId(), context.attemptNumber(), deadline);
                    long remaining = Math.min(remainingNanos(), deadline.timeRemaining(TimeUnit.NANOSECONDS));
                    if (remaining <= 0) { throw deadlineExceeded(); }
                    attempt = Objects.requireNonNull(preparedInvoker.invoke(request, lease, Duration.ofNanos(remaining), attemptContext),
                            "RPC invoker returned no attempt");
                    state = new AttemptState(lease, attempt, attemptContext);
                    active.set(state);
                    lease = null;
                    state.timeout = timer().schedule(() -> runCaptured(() -> attemptTimedOut(state)),
                            Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS)), TimeUnit.NANOSECONDS);
                } catch (Exception exception) {
                    // Setup/credential/interceptor failures never enter the retry path.
                    if (lease != null) {
                        if (attempt != null) { cancelTransport(attempt); }
                        try { lease.endCall(); } finally { lease.close(); }
                    }
                    finishFailure(exception);
                    return;
                }
            }
            state.completion.whenComplete((value, error) -> runCaptured(() -> completed(state, value, error)));
        }

        private void completed(AttemptState state, Object value, Throwable error) {
            synchronized (gate) {
                if (!state.release()) { return; }
                active.compareAndSet(state, null);
                if (context.terminal()) { return; }
                if (caller.isCancelled()) { cancelledByCaller(); return; }
                if (remainingNanos() <= 0 || state.attemptContext.deadline().isExpired()) {
                    finishFailure(deadlineExceeded());
                    return;
                }
                if (error != null) {
                    Throwable failure = unwrap(error);
                    if (retryable(state, failure)) { scheduleRetry(failure); }
                    else { finishFailure(failure); }
                } else if (value == null || !plan.responseType().isInstance(value)) {
                    finishFailure(new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL, "RPC Provider returned an invalid response type"));
                } else if (context.terminate()) { result.complete(value); }
            }
        }

        private boolean retryable(AttemptState state, Throwable failure) {
            return approvedMethod() && plan.policy().retries() == 1 && context.hasRemainingAttempt()
                    && !state.attemptContext.responseCommitted()
                    && failure instanceof StatusRuntimeException status
                    && status.getStatus().getCode() == Status.Code.UNAVAILABLE
                    && statusMapper.map(status).getRpcErrorCode() == EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE;
        }

        private void scheduleRetry(Throwable lastFailure) {
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(25, 51));
            if (remainingNanos() <= delayNanos) { finishFailure(lastFailure); return; }
            try {
                backoff = timer().schedule(() -> runCaptured(this::startAttempt), delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException exception) { finishFailure(exception); }
        }

        private void attemptTimedOut(AttemptState state) {
            synchronized (gate) {
                if (active.get() == state && !context.terminal()) { finishFailure(deadlineExceeded()); }
            }
        }

        private void cancelledByCaller() {
            if (totalDeadline.isExpired()) { finishFailure(deadlineExceeded()); }
            else { finishFailure(new EgonRpcException(EgonRpcErrorCode.RPC_CANCELLED, "RPC call was cancelled")); }
        }

        private void finishFailure(Throwable failure) {
            synchronized (gate) {
                if (!context.terminate()) { return; }
                cancelActive();
                result.completeExceptionally(map(failure));
            }
        }

        private void cancelActive() {
            AttemptState state = active.getAndSet(null);
            if (state != null) { state.cancel(); }
        }

        private void cleanup() {
            synchronized (gate) {
                if (backoff != null) { backoff.cancel(false); backoff = null; }
                if (logicalCaller != null) {
                    logicalCaller.removeListener(callerCancellation);
                    logicalCaller.cancel(null);
                }
                invocations.remove(this);
            }
        }

        private void runCaptured(Runnable action) {
            Context captured = logicalCaller == null ? caller : logicalCaller;
            captured.run(() -> {
                try (TraceContext.Scope ignored = trace.open()) { action.run(); }
            });
        }

        private final class ExecutionFuture extends CompletableFuture<Object> {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (gate) {
                    if (!context.terminate()) { return false; }
                    cancelActive();
                    return super.cancel(mayInterruptIfRunning);
                }
            }
        }
    }

    private static final class AttemptState {
        private final RpcChannelLease lease;
        private final RpcInvocationPlan.Attempt attempt;
        private final RpcInvocationPlan.AttemptContextVO attemptContext;
        private final CompletableFuture<Object> completion;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean transportCancelled = new AtomicBoolean();
        private ScheduledFuture<?> timeout;

        private AttemptState(RpcChannelLease lease, RpcInvocationPlan.Attempt attempt,
                             RpcInvocationPlan.AttemptContextVO attemptContext) {
            this.lease = lease;
            this.attempt = attempt;
            this.attemptContext = attemptContext;
            this.completion = attempt.completion().toCompletableFuture();
        }

        private boolean release() {
            if (!released.compareAndSet(false, true)) { return false; }
            if (timeout != null) { timeout.cancel(false); }
            try { lease.endCall(); } finally { lease.close(); }
            return true;
        }

        private void cancel() {
            try {
                if (transportCancelled.compareAndSet(false, true)) { cancelTransport(attempt); }
                completion.cancel(false);
            } finally { release(); }
        }
    }

    private static void cancelTransport(RpcInvocationPlan.Attempt attempt) {
        try { attempt.cancel().run(); }
        catch (RuntimeException exception) { log.debug("RPC transport cancellation failed: {}", exception.getClass().getName()); }
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

    private static EgonRpcException unavailable() {
        return new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE, "RPC invocation executor is closed");
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
