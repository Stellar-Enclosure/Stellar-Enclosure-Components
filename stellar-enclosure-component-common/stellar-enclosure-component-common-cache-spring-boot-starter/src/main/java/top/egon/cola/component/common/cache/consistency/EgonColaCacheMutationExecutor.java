package top.egon.cola.component.common.cache.consistency;

import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheProperties;
import top.egon.cola.component.common.cache.core.EgonColaTwoLevelCacheManager;
import top.egon.cola.component.common.cache.core.EgonColaCacheTransactionState;
import top.egon.cola.component.common.cache.event.EgonColaCacheChangedEvent.KeyGuard;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Runs an explicit business write with a configured, replaceable invalidation strategy. */
public final class EgonColaCacheMutationExecutor {

    private final EgonColaTwoLevelCacheManager manager;
    private final EgonColaCacheProperties properties;
    private final Map<String, EgonColaCacheConsistencyStrategy> strategies;

    public EgonColaCacheMutationExecutor(EgonColaTwoLevelCacheManager manager, EgonColaCacheProperties properties,
                                        List<EgonColaCacheConsistencyStrategy> customStrategies) {
        this.manager = manager;
        this.properties = properties;
        Map<String, EgonColaCacheConsistencyStrategy> registered = new LinkedHashMap<>();
        for (var strategy : EgonColaBuiltinCacheConsistencyStrategy.values()) {
            registered.put(strategy.name(), strategy);
        }
        for (var strategy : customStrategies) {
            if (strategy.name() == null || strategy.name().isBlank()
                    || registered.putIfAbsent(strategy.name(), strategy) != null) {
                throw new IllegalArgumentException("CACHE_CONSISTENCY_STRATEGY_DUPLICATE");
            }
        }
        strategies = Map.copyOf(registered);
        resolve(properties.getConsistencyStrategy());
        properties.getRegions().values().stream().map(EgonColaCacheProperties.RegionTtl::getConsistencyStrategy)
                .filter(value -> value != null && !value.isBlank()).forEach(this::resolve);
        validateDelay(properties.getSecondEvictDelay());
    }

    public <T> T execute(String cacheName, Collection<String> keys, Supplier<T> write) {
        return execute(null, cacheName, keys, List.of(), write);
    }

    public <T> T execute(String strategyName, String cacheName, Collection<String> keys,
                         Collection<String> globPatterns, Supplier<T> write) {
        if (write == null) {
            throw new IllegalArgumentException("CACHE_WRITE_REQUIRED");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("CACHE_TRANSACTION_SYNCHRONIZATION_REQUIRED");
        }
        KeyGuard.requireValidName(cacheName);
        long tenant = currentTenant();
        List<String> exact = keys == null ? List.of() : List.copyOf(keys);
        List<String> globs = globPatterns == null ? List.of() : List.copyOf(globPatterns);
        if (exact.isEmpty() && globs.isEmpty()) {
            throw new IllegalArgumentException("CACHE_EVICTION_TARGET_REQUIRED");
        }
        exact.forEach(key -> KeyGuard.requireTenant(key, tenant));
        for (String glob : globs) {
            KeyGuard.requireGlob(glob);
            if (!glob.equals(tenant + ":*")) {
                throw new IllegalArgumentException("CACHE_KEY_TENANT_MISMATCH");
            }
        }
        EgonColaCacheConsistencyStrategy strategy = resolve(selectStrategy(strategyName, cacheName));
        var mutation = new EgonColaCacheMutation(manager, cacheName, exact, globs, properties.getSecondEvictDelay());
        EgonColaCacheTransactionState.mark(cacheName, exact, globs);
        strategy.beforeWrite(mutation);
        T result = write.get();
        strategy.afterWrite(mutation);
        return result;
    }

    private String selectStrategy(String requested, String cacheName) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        var region = properties.getRegions().get(cacheName);
        return region != null && region.getConsistencyStrategy() != null && !region.getConsistencyStrategy().isBlank()
                ? region.getConsistencyStrategy() : properties.getConsistencyStrategy();
    }

    private EgonColaCacheConsistencyStrategy resolve(String name) {
        var strategy = strategies.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("CACHE_CONSISTENCY_STRATEGY_UNKNOWN: " + name);
        }
        return strategy;
    }

    private long currentTenant() {
        String raw = MDC.get(properties.getTenantMdcKey());
        if (raw == null || !raw.matches("[0-9]+")) {
            throw new IllegalArgumentException("CACHE_KEY_TENANT_MISMATCH");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("CACHE_KEY_TENANT_MISMATCH", failure);
        }
    }

    private static void validateDelay(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("CACHE_EVICTION_DELAY_INVALID");
        }
        try {
            delay.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("CACHE_EVICTION_DELAY_INVALID", failure);
        }
    }
}
