package top.egon.cola.component.rpc.consumer.channel;

import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns one ManagedChannel per stable transport key and drains active calls at shutdown. */
@Slf4j
public final class RpcConsumerChannelPool implements SmartLifecycle, AutoCloseable {

    private final Object monitor = new Object();
    private final RpcConsumerChannelFactory channelFactory;
    private final long drainTimeoutMs;
    private final Map<RpcChannelKey, Entry> entries = new HashMap<>();
    private final ScheduledExecutorService drainExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "egon-rpc-channel-drain");
                thread.setDaemon(true);
                return thread;
            });
    private boolean closed;

    public RpcConsumerChannelPool(RpcConsumerChannelFactory channelFactory, Duration drainTimeout) {
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        if (drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()) {
            throw new IllegalArgumentException("drainTimeout must be positive");
        }
        drainTimeoutMs = Math.max(1L, drainTimeout.toMillis());
    }

    public RpcConsumerChannelPool(RpcConsumerChannelFactory channelFactory, long drainTimeoutMs) {
        this(channelFactory, Duration.ofMillis(drainTimeoutMs));
    }

    public RpcChannelLease acquire(RpcServiceTargetVO target) {
        return acquire(RpcChannelKey.from(target));
    }

    public RpcChannelLease acquire(RpcChannelKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (monitor) {
            if (closed) {
                throw unavailable("RPC channel pool is draining");
            }
            Entry entry = entries.computeIfAbsent(key, candidate -> new Entry(candidate, channelFactory.create(candidate)));
            entry.references++;
            return new RpcChannelLease(entry);
        }
    }

    @Override
    public void start() {
        // The channel pool is ready before the consumer lifecycle starts accepting invocations.
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        synchronized (monitor) {
            return !closed;
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 80;
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            log.debug("Draining the internal RPC channel pool");
            new ArrayList<>(entries.values()).forEach(Entry::beginDrain);
            // Already scheduled force-close tasks run after executor shutdown.
            drainExecutor.shutdown();
        }
    }

    private EgonRpcException unavailable(String message) {
        return new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE, message);
    }

    final class Entry {

        private final RpcChannelKey key;
        private final ManagedChannel channel;
        private int references;
        private int inFlight;
        private boolean draining;
        private ScheduledFuture<?> forceTask;

        private Entry(RpcChannelKey key, ManagedChannel channel) {
            this.key = key;
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        ManagedChannel channel() {
            return channel;
        }

        void beginCall() {
            synchronized (monitor) {
                if (draining) {
                    throw unavailable("RPC channel entry is draining");
                }
                inFlight++;
            }
        }

        void endCall() {
            synchronized (monitor) {
                inFlight--;
                if (inFlight == 0 && forceTask != null) {
                    forceTask.cancel(false);
                    forceTask = null;
                }
            }
        }

        void releaseReference() {
            synchronized (monitor) {
                references--;
                if (references == 0) {
                    beginDrain();
                }
            }
        }

        private void beginDrain() {
            if (draining) {
                return;
            }
            draining = true;
            entries.remove(key, this);
            channel.shutdown();
            if (inFlight > 0) {
                forceTask = drainExecutor.schedule(this::forceClose, drainTimeoutMs, TimeUnit.MILLISECONDS);
            }
        }

        private void forceClose() {
            synchronized (monitor) {
                if (forceTask != null) {
                    forceTask = null;
                    channel.shutdownNow();
                }
            }
        }
    }
}
