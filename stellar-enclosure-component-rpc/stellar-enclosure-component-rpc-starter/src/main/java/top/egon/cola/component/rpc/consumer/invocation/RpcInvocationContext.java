package top.egon.cola.component.rpc.consumer.invocation;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A logical identifier, monotonic total budget and bounded attempt count. */
public final class RpcInvocationContext {
    private final String invocationId = UUID.randomUUID().toString();
    private final long startedNanos;
    private final long timeoutNanos;
    private final int maxAttempts;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicBoolean terminal = new AtomicBoolean();

    public RpcInvocationContext(long nowNanos, long timeoutMs) {
        this(nowNanos, Duration.ofNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs)), 1);
        if (timeoutMs <= 0) { throw new IllegalArgumentException("RPC timeout must be positive"); }
    }

    public RpcInvocationContext(long nowNanos, Duration timeout, int maxAttempts) {
        if (timeout == null || timeout.isNegative() || maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("RPC budget and attempt limit are invalid");
        }
        this.startedNanos = nowNanos;
        this.timeoutNanos = timeout.toNanos();
        this.maxAttempts = maxAttempts;
    }

    public String invocationId() { return invocationId; }
    public String logicalCallId() { return invocationId; }
    public int attemptNumber() { return attempts.get(); }
    public boolean hasRemainingAttempt() { return !terminal.get() && attempts.get() < maxAttempts; }
    public boolean beginAttempt() {
        while (!terminal.get()) {
            int current = attempts.get();
            if (current >= maxAttempts) { return false; }
            if (attempts.compareAndSet(current, current + 1)) { return true; }
        }
        return false;
    }
    public long remainingNanos(long nowNanos) {
        long elapsed = nowNanos - startedNanos;
        return elapsed < 0 || elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }
    public boolean expired(long nowNanos) { return remainingNanos(nowNanos) == 0; }
    public boolean terminate() { return terminal.compareAndSet(false, true); }
    public boolean terminal() { return terminal.get(); }
}
