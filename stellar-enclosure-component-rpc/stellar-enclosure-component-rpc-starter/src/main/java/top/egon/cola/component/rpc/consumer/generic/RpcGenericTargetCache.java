package top.egon.cola.component.rpc.consumer.generic;

import io.grpc.MethodDescriptor;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinition;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** Bounded cache keyed by trusted target revision, logical descriptor and exact unary method. */
public final class RpcGenericTargetCache implements AutoCloseable {

    @FunctionalInterface
    public interface StrategyCreator {
        RpcReferenceStrategy create(RpcReferenceDefinition definition);
    }

    private final RpcReferenceDefinitionResolver resolver;
    private final StrategyCreator strategyCreator;
    private final int maxEntries;
    private final long idleTimeoutMs;
    private final LongSupplier clockMs;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public RpcGenericTargetCache(RpcReferenceDefinitionResolver resolver, RpcReferenceStrategyFactory strategyFactory,
                                 int maxEntries, Duration idleTimeout) {
        this(resolver, Objects.requireNonNull(strategyFactory, "strategyFactory")::create,
                maxEntries, idleTimeout, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public RpcGenericTargetCache(RpcReferenceDefinitionResolver resolver, StrategyCreator strategyCreator,
                                 int maxEntries, Duration idleTimeout, LongSupplier clockMs) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.strategyCreator = Objects.requireNonNull(strategyCreator, "strategyCreator");
        if (maxEntries < 1) { throw new IllegalArgumentException("maxEntries must be positive"); }
        if (idleTimeout == null || idleTimeout.toMillis() < 1_000) {
            throw new IllegalArgumentException("idleTimeout must be at least one second");
        }
        this.maxEntries = maxEntries;
        this.idleTimeoutMs = idleTimeout.toMillis();
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    synchronized Entry resolve(RpcGenericInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        if (closed) { throw new IllegalStateException("generic target cache is closed"); }
        RpcServiceIdentity identity = new RpcServiceIdentity(invocation.serviceName(), invocation.group(), invocation.version());
        // Authorization is checked before every cache lookup, including cache hits.
        RpcReferenceDefinition definition = resolver.resolve(invocation.bindingId(), identity,
                Set.of(invocation.fullMethodName()));
        Key key = new Key(definition.target(), identity, invocation.fullMethodName());
        Entry entry = entries.get(key);
        if (entry == null) {
            evictIdle();
            if (entries.size() >= maxEntries) {
                Entry candidate = entries.values().stream().filter(value -> value.inUse == 0).findFirst().orElse(null);
                if (candidate == null) { throw new IllegalStateException("generic target cache is at its active-entry limit"); }
                entries.remove(candidate.key);
                candidate.retire();
            }
            RpcReferenceStrategy strategy = Objects.requireNonNull(strategyCreator.create(definition), "strategy");
            if (!definition.target().equals(strategy.target())) {
                strategy.close();
                throw new IllegalArgumentException("generic strategy does not match the trusted binding");
            }
            entry = new Entry(key, strategy, RpcGenericInvoker.rawMethod(invocation.fullMethodName()), clockMs.getAsLong());
            entries.put(key, entry);
        }
        entry.inUse++;
        entry.lastAccessMs = clockMs.getAsLong();
        return entry;
    }

    long defaultTimeoutMs() { return resolver.defaultTimeoutMs(); }

    public synchronized void evictIdle() {
        long now = clockMs.getAsLong();
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.inUse == 0 && now - entry.lastAccessMs >= idleTimeoutMs) {
                iterator.remove();
                entry.retire();
            }
        }
    }

    public synchronized int size() { return entries.size(); }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        var closing = new ArrayList<>(entries.values());
        entries.clear();
        closing.forEach(Entry::retire);
    }

    private synchronized void release(Entry entry) {
        if (entry.inUse == 0) { return; }
        entry.inUse--;
        entry.lastAccessMs = clockMs.getAsLong();
        entry.closeIfRetired();
    }

    private record Key(RpcServiceTargetVO target, RpcServiceIdentity identity, String fullMethodName) { }

    /** Internal shared entry: each successful resolve grants one borrow, released once by the invoker. */
    final class Entry implements AutoCloseable {
        private final Key key;
        private final RpcReferenceStrategy strategy;
        private final MethodDescriptor<byte[], byte[]> method;
        private int inUse;
        private long lastAccessMs;
        private boolean retired;
        private boolean strategyClosed;

        private Entry(Key key, RpcReferenceStrategy strategy, MethodDescriptor<byte[], byte[]> method, long lastAccessMs) {
            this.key = key;
            this.strategy = strategy;
            this.method = method;
            this.lastAccessMs = lastAccessMs;
        }

        public RpcReferenceStrategy strategy() { return strategy; }
        public MethodDescriptor<byte[], byte[]> method() { return method; }
        public int inUse() { synchronized (RpcGenericTargetCache.this) { return inUse; } }
        public void release() { RpcGenericTargetCache.this.release(this); }
        @Override public void close() { release(); }

        private void retire() { retired = true; closeIfRetired(); }
        private void closeIfRetired() {
            if (retired && inUse == 0 && !strategyClosed) {
                strategyClosed = true;
                strategy.close();
            }
        }
    }
}
