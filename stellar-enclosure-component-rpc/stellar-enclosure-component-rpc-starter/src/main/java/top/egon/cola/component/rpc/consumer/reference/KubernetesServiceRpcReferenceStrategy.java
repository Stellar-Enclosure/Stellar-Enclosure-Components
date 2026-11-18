package top.egon.cola.component.rpc.consumer.reference;

import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;

import java.util.Objects;
import java.util.function.Consumer;

/** Holds an anchor lease so repeated calls reuse one binding/version channel. */
@Slf4j
public final class KubernetesServiceRpcReferenceStrategy implements RpcReferenceStrategy {
    private final RpcServiceTargetVO target;
    private final RpcConsumerChannelPool pool;
    private final RpcChannelLease anchor;
    private final Consumer<RpcReferenceStrategy> onClose;
    private boolean closed;

    public KubernetesServiceRpcReferenceStrategy(RpcReferenceDefinition definition, RpcConsumerChannelPool pool) {
        this(definition, pool, ignored -> { });
    }

    KubernetesServiceRpcReferenceStrategy(RpcReferenceDefinition definition, RpcConsumerChannelPool pool,
            Consumer<RpcReferenceStrategy> onClose) {
        this.target = Objects.requireNonNull(definition, "definition").target();
        this.pool = Objects.requireNonNull(pool, "pool");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        this.anchor = Objects.requireNonNull(pool.acquire(target), "anchor lease");
    }

    @Override public RpcServiceTargetVO target() { return target; }
    @Override public String queryIdentity() { return target.bindingId() + "@" + target.version(); }
    @Override public long revision() { return target.version(); }

    @Override public synchronized RpcChannelLease acquire() {
        if (closed) {
            throw new IllegalStateException("RPC reference strategy is closed");
        }
        return pool.acquire(target);
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            anchor.close();
        } finally {
            onClose.accept(this);
        }
    }
}
