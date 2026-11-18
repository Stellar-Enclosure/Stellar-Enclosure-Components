package top.egon.cola.component.rpc.consumer.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns deterministic Consumer start, admission gate and reverse-order drain. */
@Slf4j
public final class RpcConsumerLifecycleCoordinator
        implements SmartLifecycle, AutoCloseable {

    private final Object monitor = new Object();
    private final RpcConsumerChannelPool pool;
    private final List<? extends AutoCloseable> closeHooks;

    private volatile RpcConsumerRuntimeState state = RpcConsumerRuntimeState.NEW;

    private final AtomicBoolean stopCallbackInvoked = new AtomicBoolean();

    public RpcConsumerLifecycleCoordinator(
            RpcConsumerChannelPool pool,
            List<? extends AutoCloseable> closeHooks) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.closeHooks = closeHooks == null ? List.of() : List.copyOf(closeHooks);
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (state == RpcConsumerRuntimeState.READY
                    || state == RpcConsumerRuntimeState.DEGRADED) {
                return;
            }
            if (state == RpcConsumerRuntimeState.DRAINING
                    || state == RpcConsumerRuntimeState.FAILED) {
                throw unavailable("RPC Consumer is not restartable after drain/failure");
            }
            state = RpcConsumerRuntimeState.STARTING;
            try {
                pool.start();
                if (!pool.isRunning()) {
                    throw unavailable("RPC channel pool is closed");
                }
                state = RpcConsumerRuntimeState.READY;
            } catch (RuntimeException exception) {
                state = RpcConsumerRuntimeState.FAILED;
                cleanupAfterFailure();
                throw exception;
            }
        }
    }

    public void markDegraded() {
        synchronized (monitor) {
            if (state == RpcConsumerRuntimeState.READY) {
                state = RpcConsumerRuntimeState.DEGRADED;
            }
        }
    }

    public RpcConsumerRuntimeState state() {
        return state;
    }

    public void requireAccepting() {
        if (!state.accepting()) {
            throw unavailable("RPC Consumer is not accepting new invocations: " + state);
        }
    }

    @Override
    public boolean isRunning() {
        return state.accepting();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 70;
    }

    @Override
    public void stop() {
        stop(null);
    }

    @Override
    public void stop(Runnable callback) {
        synchronized (monitor) {
            if (state == RpcConsumerRuntimeState.STOPPED) {
                runOnce(callback);
                return;
            }
            state = RpcConsumerRuntimeState.DRAINING;
            closeHooks();
            pool.close();
            state = RpcConsumerRuntimeState.STOPPED;
            runOnce(callback);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void cleanupAfterFailure() {
        closeHooks();
        pool.close();
    }

    private void closeHooks() {
        List<AutoCloseable> reverse = new ArrayList<>(closeHooks);
        java.util.Collections.reverse(reverse);
        for (AutoCloseable hook : reverse) {
            try {
                hook.close();
            } catch (Exception failure) {
                log.warn("RPC consumer cleanup hook failed ({})", failure.getClass().getSimpleName());
            }
        }
    }

    private void runOnce(Runnable callback) {
        if (callback != null && stopCallbackInvoked.compareAndSet(false, true)) {
            callback.run();
        }
    }

    private EgonRpcException unavailable(String message) {
        return new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE, message);
    }
}
