package top.egon.cola.component.rpc.consumer.reference;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Creates only the Kubernetes Service strategy and releases its anchor on shutdown. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class RpcReferenceStrategyFactory implements AutoCloseable {
    @NonNull
    @Qualifier("rpcConsumerChannelPool")
    private final RpcConsumerChannelPool pool;
    private final Set<RpcReferenceStrategy> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public synchronized RpcReferenceStrategy create(RpcReferenceDefinition definition) {
        if (closed) {
            throw new IllegalStateException("RPC reference strategy factory is closed");
        }
        var strategy = new KubernetesServiceRpcReferenceStrategy(
                Objects.requireNonNull(definition, "definition"), pool, this::released);
        active.add(strategy);
        return strategy;
    }

    private synchronized void released(RpcReferenceStrategy strategy) {
        active.remove(strategy);
    }

    @Override public void close() {
        Set<RpcReferenceStrategy> owned;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            owned = Set.copyOf(active);
            active.clear();
        }
        RuntimeException failure = null;
        for (RpcReferenceStrategy strategy : owned) {
            try {
                strategy.close();
            }
            catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
