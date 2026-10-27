package top.egon.cola.component.common.cache.core;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Prevents a transaction's replica read from republishing a key it also invalidated. */
public final class EgonColaCacheTransactionState {

    private static final Object RESOURCE_KEY = new Object();

    private final Map<String, Set<String>> keys = new HashMap<>();
    private final Map<String, Set<String>> tenantPrefixes = new HashMap<>();
    private final Map<EgonColaTwoLevelCache, Map<String, Long>> readRevisions = new IdentityHashMap<>();

    private EgonColaCacheTransactionState() {
    }

    public static void mark(String cacheName, Collection<String> keys, Collection<String> globs) {
        var state = currentOrCreate();
        if (state == null) {
            return;
        }
        state.keys.computeIfAbsent(cacheName, ignored -> new HashSet<>()).addAll(keys);
        Set<String> prefixes = state.tenantPrefixes.computeIfAbsent(cacheName, ignored -> new HashSet<>());
        globs.forEach(glob -> prefixes.add(glob.substring(0, glob.length() - 1)));
    }

    static void recordRead(EgonColaTwoLevelCache cache, String key, long revision) {
        var state = currentOrCreate();
        if (state != null) {
            state.readRevisions.computeIfAbsent(cache, ignored -> new HashMap<>()).putIfAbsent(key, revision);
        }
    }

    static long readRevision(EgonColaTwoLevelCache cache, String key, long fallback) {
        var state = (EgonColaCacheTransactionState) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        return state == null ? fallback : state.readRevisions.getOrDefault(cache, Map.of()).getOrDefault(key, fallback);
    }

    private static EgonColaCacheTransactionState currentOrCreate() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        var state = (EgonColaCacheTransactionState) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (state == null) {
            state = new EgonColaCacheTransactionState();
            var registered = state;
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, state);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(RESOURCE_KEY, registered);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
                }
            });
        }
        return state;
    }

    static boolean invalidated(String cacheName, String key) {
        var state = (EgonColaCacheTransactionState) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (state == null) {
            return false;
        }
        return state.keys.getOrDefault(cacheName, Set.of()).contains(key)
                || state.tenantPrefixes.getOrDefault(cacheName, Set.of()).stream().anyMatch(key::startsWith);
    }
}
