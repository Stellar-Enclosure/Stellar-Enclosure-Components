package top.egon.cola.component.rpc.consumer.invocation;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deadline and terminal state owned by one logical invocation. */
public final class RpcInvocationContext {

    private final String invocationId = UUID.randomUUID().toString();
    private final long startedNanos;
    private final long timeoutNanos;
    private final AtomicBoolean attempted = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();

    public RpcInvocationContext(long nowNanos, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("RPC timeout must be positive");
        }
        this.startedNanos = nowNanos;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    public String invocationId() { return invocationId; }

    public boolean beginAttempt() {
        return !terminal.get() && attempted.compareAndSet(false, true);
    }

    public long remainingNanos(long nowNanos) {
        long elapsed = nowNanos - startedNanos;
        return elapsed < 0 || elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }

    public boolean expired(long nowNanos) { return remainingNanos(nowNanos) == 0; }
    public boolean terminate() { return terminal.compareAndSet(false, true); }
    public boolean terminal() { return terminal.get(); }
}
