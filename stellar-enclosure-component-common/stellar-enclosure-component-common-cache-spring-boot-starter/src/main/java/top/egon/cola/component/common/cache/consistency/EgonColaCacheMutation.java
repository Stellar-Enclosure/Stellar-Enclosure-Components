package top.egon.cola.component.common.cache.consistency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.cache.core.EgonColaTwoLevelCacheManager;
import top.egon.cola.component.common.cache.event.EgonColaCacheChangedOperation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Validated immutable invalidation target available to built-in and host strategies. */
@Slf4j
public final class EgonColaCacheMutation {

    private final EgonColaTwoLevelCacheManager manager;
    private final String cacheName;
    private final List<String> keys;
    private final List<String> globs;
    private final Duration delay;

    EgonColaCacheMutation(EgonColaTwoLevelCacheManager manager, String cacheName, List<String> keys,
                          List<String> globs, Duration delay) {
        this.manager = manager;
        this.cacheName = cacheName;
        this.keys = List.copyOf(keys);
        this.globs = List.copyOf(globs);
        this.delay = delay;
    }

    public void evictNow() {
        if (!keys.isEmpty()) {
            manager.applyLocalEviction(cacheName, keys);
            manager.publish(cacheName, EgonColaCacheChangedOperation.EVICT, keys);
        }
        for (String glob : globs) {
            manager.applyLocalPrefixEviction(cacheName, glob);
            manager.publish(cacheName, EgonColaCacheChangedOperation.PREFIX_EVICT, List.of(glob));
        }
    }

    public void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("CACHE_TRANSACTION_SYNCHRONIZATION_REQUIRED");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public void evictAfterCommit() {
        afterCommit(this::evictNow);
    }

    public void evictAfterCommitWithDelay() {
        afterCommit(() -> {
            try {
                manager.scheduler().schedule(() -> {
                    try {
                        evictNow();
                    } catch (RuntimeException failure) {
                        log.warn("CACHE_L2_OPERATION_FAILED region={} op=DELAYED_EVICT", cacheName, failure);
                    }
                }, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException failure) {
                log.warn("CACHE_L2_OPERATION_FAILED region={} op=SCHEDULE_EVICT", cacheName, failure);
            }
        });
    }
}
