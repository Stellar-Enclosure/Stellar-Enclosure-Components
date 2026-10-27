package top.egon.cola.component.common.cache.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原生 Spring 代理 + mock Redis；不启动网络服务或容器。
 */
class EgonColaCacheAnnotationTest {

    private AnnotationConfigApplicationContext context;
    private EgonColaTwoLevelCacheManager manager;
    private EgonColaCacheProperties properties;
    private ExampleRepository repository;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, Map<String, Object>> data = new ConcurrentHashMap<>();
    private final Map<String, Long> ttls = new ConcurrentHashMap<>();
    private RedissonClient redis;
    private RLock distributedLock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MDC.put("tenantId", "41");
        properties = new EgonColaCacheProperties();
        properties.getTtl().setL1Jitter(Duration.ZERO);
        properties.getTtl().setL2Jitter(Duration.ZERO);
        redis = mock(RedissonClient.class);
        distributedLock = mock(RLock.class);
        ReentrantLock lock = new ReentrantLock();
        when(redis.getLock(anyString())).thenReturn(distributedLock);
        when(distributedLock.tryLock(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            lock.lock();
            return true;
        });
        when(distributedLock.isHeldByCurrentThread()).thenAnswer(invocation -> lock.isHeldByCurrentThread());
        org.mockito.Mockito.doAnswer(invocation -> {
            lock.unlock();
            return null;
        }).when(distributedLock).unlock();
        when(redis.getTopic(anyString(), any(Codec.class))).thenReturn(mock(RTopic.class));
        when(redis.getMapCache(anyString(), any(Codec.class))).thenAnswer(invocation -> {
            String region = invocation.getArgument(0);
            Map<String, Object> values = data.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
            RMapCache<String, Object> map = mock(RMapCache.class);
            when(map.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(map.remainTimeToLive(anyString())).thenAnswer(call -> values.containsKey(call.getArgument(0))
                    ? ttls.getOrDefault(region + ":" + call.getArgument(0), 60000L) : -2L);
            when(map.fastPut(anyString(), any(), anyLong(), any())).thenAnswer(call -> {
                values.put(call.getArgument(0), call.getArgument(1));
                ttls.put(region + ":" + call.getArgument(0), call.getArgument(2));
                return true;
            });
            when(map.readAllKeySet()).thenAnswer(call -> new HashSet<>(values.keySet()));
            when(map.fastRemove(any(String[].class))).thenAnswer(call -> {
                long removed = 0;
                for (Object key : call.getArguments()) {
                    if (values.remove(key) != null) {
                        removed++;
                    }
                }
                return removed;
            });
            return map;
        });
        manager = new EgonColaTwoLevelCacheManager(properties, redis);
        context = new AnnotationConfigApplicationContext();
        context.register(CacheConfiguration.class);
        context.registerBean("cacheManager", EgonColaTwoLevelCacheManager.class, () -> manager);
        context.registerBean(ExampleRepository.class, () -> new ExampleRepository(calls));
        context.refresh();
        repository = context.getBean(ExampleRepository.class);
    }

    @AfterEach
    void close() {
        context.close();
        MDC.clear();
    }

    @Test
    void cacheConfigSpelConditionAndUnlessUseNativeSpringSemantics() {
        assertThat(repository.find("alice", true)).isEqualTo("alice");
        repository.find("alice", true);
        assertThat(calls).hasValue(1);
        repository.find("alice", false);
        assertThat(calls).hasValue(2);
        repository.find("missing", true);
        repository.find("missing", true);
        assertThat(calls).hasValue(4);
        assertThat(manager.getCache("users").get("41:user:missing")).isNull();
    }

    @Test
    void cachePutAndCachingUpdateAndInvalidateSeparateRegions() {
        repository.find("alice", true);
        manager.getCache("search").put("41:query:all", "old");
        assertThat(repository.update("alice", "new")).isEqualTo("new");
        assertThat(repository.find("alice", true)).isEqualTo("new");
        assertThat(manager.getCache("search").get("41:query:all")).isNull();
        assertThat(calls).hasValue(2);
        repository.delete("alice");
        assertThat(repository.find("alice", true)).isEqualTo("alice");
    }

    @Test
    void allEntriesIsTenantScopedAndPublishesPrefixInvalidation() {
        Cache cache = manager.getCache("search");
        cache.put("41:query:all", "a");
        MDC.put("tenantId", "42");
        cache.put("42:query:all", "b");
        MDC.put("tenantId", "41");
        repository.update("alice", "new");
        assertThat(cache.get("41:query:all")).isNull();
        MDC.put("tenantId", "42");
        assertThat(cache.get("42:query:all").get()).isEqualTo("b");
        org.mockito.Mockito.verify(manager.topic(), org.mockito.Mockito.atLeastOnce())
                .publish(org.mockito.ArgumentMatchers.contains("PREFIX_EVICT"));
    }

    @Test
    void syncCachesNullAndRejectsUnlessCombination() {
        assertThat(repository.sync("missing")).isNull();
        assertThat(repository.sync("missing")).isNull();
        assertThat(calls).hasValue(1);
        assertThat(ttls.get(properties.getKeyPrefix() + ":users:41:user:missing")).isEqualTo(60000);
        assertThatThrownBy(() -> repository.invalidSync("a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unless");
    }

    @Test
    void syncCoalescesConcurrentLoads() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    MDC.put("tenantId", "41");
                    try {
                        gate.await();
                        return repository.sync("alice");
                    } finally {
                        MDC.clear();
                    }
                }));
            }
            gate.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("alice");
            }
            assertThat(calls).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mutationsWaitForCommitAndRollbackDiscardsThem() {
        repository.find("alice", true);
        TransactionTemplate transaction = new TransactionTemplate(new LocalTransactionManager());
        transaction.executeWithoutResult(status -> {
            repository.update("alice", "rollback");
            status.setRollbackOnly();
        });
        assertThat(repository.find("alice", true)).isEqualTo("alice");
        transaction.executeWithoutResult(status -> repository.update("alice", "committed"));
        assertThat(repository.find("alice", true)).isEqualTo("committed");
        transaction.executeWithoutResult(status -> {
            repository.delete("alice");
            status.setRollbackOnly();
        });
        assertThat(repository.find("alice", true)).isEqualTo("committed");
        transaction.executeWithoutResult(status -> repository.delete("alice"));
        assertThat(manager.getCache("users").get("41:user:alice")).isNull();
    }

    @Test
    void transactionReadsBypassCacheAndNeverPublishRolledBackLoads() {
        repository.update("alice", "cached");
        TransactionTemplate transaction = new TransactionTemplate(new LocalTransactionManager());
        transaction.executeWithoutResult(status -> {
            assertThat(repository.sync("alice")).isEqualTo("alice");
            assertThat(repository.sync("missing")).isNull();
            status.setRollbackOnly();
        });
        assertThat(repository.find("alice", true)).isEqualTo("cached");
        assertThat(manager.getCache("users").get("41:user:missing")).isNull();
    }

    @Test
    void beforeInvocationEvictsEvenWhenMethodFailsAndTransactionRollsBack() {
        repository.find("alice", true);
        TransactionTemplate transaction = new TransactionTemplate(new LocalTransactionManager());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> repository.deleteBefore("alice")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.getCache("users").get("41:user:alice")).isNull();
    }

    @Test
    void selfInvocationBypassesProxyAndProgrammaticCacheRemainsAvailable() {
        repository.selfFind("alice");
        repository.selfFind("alice");
        assertThat(calls).hasValue(2);
        assertThat(manager.getCache("users").get("41:user:alice")).isNull();
        assertThat(manager.getCache("users").get("41:user:alice", () -> "manual")).isEqualTo("manual");
        assertThat(repository.find("alice", true)).isEqualTo("manual");
    }

    @Test
    void rejectsMissingTenantAndCrossTenantBeforeCacheHits() {
        repository.find("alice", true);
        MDC.remove("tenantId");
        assertThatThrownBy(() -> manager.getCache("users").get("41:user:alice"))
                .hasMessageContaining("CACHE_KEY_TENANT_MISMATCH");
        MDC.put("tenantId", "42");
        assertThatThrownBy(() -> manager.getCache("users").get("41:user:alice"))
                .hasMessageContaining("CACHE_KEY_TENANT_MISMATCH");
        assertThatThrownBy(() -> manager.getCache("users").put("42:*", "x"))
                .hasMessageContaining("CACHE_GLOB_PATTERN_FORBIDDEN");
    }

    @Test
    void regionTtlOverridesGlobalDefaultsAndAddsBoundedJitter() {
        EgonColaCacheProperties.RegionTtl override = new EgonColaCacheProperties.RegionTtl();
        override.setL1Expire(Duration.ofSeconds(4));
        override.setL1Jitter(Duration.ofSeconds(2));
        override.setL2Expire(Duration.ofSeconds(10));
        override.setL2Jitter(Duration.ofSeconds(2));
        properties.getRegions().put("users", override);
        repository.find("alice", true);
        repository.sync("missing");
        // 两级独立采样：较短的 L1 区间不改变 L2 写入寿命
        assertThat(ttls.get(properties.getKeyPrefix() + ":users:41:user:alice")).isBetween(10000L, 12000L);
        // 空值哨兵使用 nullExpire 且不加抖动
        assertThat(ttls.get(properties.getKeyPrefix() + ":users:41:user:missing")).isEqualTo(60000L);
        manager.getCache("search").put("41:query:all", "value");
        assertThat(ttls.get(properties.getKeyPrefix() + ":search:41:query:all")).isEqualTo(3600000L);
    }

    @Test
    void lockHandleAndReleaseFailuresDoNotOverrideLoaderResult() {
        doThrow(new IllegalStateException("redis down")).when(redis).getLock(anyString());
        assertThat(repository.sync("alice")).isEqualTo("alice");
        org.mockito.Mockito.doReturn(distributedLock).when(redis).getLock(anyString());
        doThrow(new IllegalStateException("redis down")).when(distributedLock).unlock();
        assertThatCode(() -> repository.sync("bob")).doesNotThrowAnyException();
    }

    @Test
    void lateRemoteInvalidationsKeepNewSharedValues() {
        Cache cache = manager.getCache("users");
        cache.put("41:user:alice", "new");
        manager.applyRemotePut("users", List.of("41:user:alice"));
        assertThat(cache.get("41:user:alice").get()).isEqualTo("new");
        manager.applyRemotePrefixEviction("users", "41:*");
        assertThat(cache.get("41:user:alice").get()).isEqualTo("new");
    }

    @Test
    void transactionDoesNotRepopulateAKeyInvalidatedEarlierInTheSameTransaction() {
        Cache users = manager.getCache("users");
        users.put("41:user:alice", "old");
        TransactionTemplate transaction = new TransactionTemplate(new LocalTransactionManager());
        transaction.executeWithoutResult(status -> {
            users.evict("41:user:alice");
            users.put("41:user:alice", "stale-replica-read");
        });
        assertThat(users.get("41:user:alice")).isNull();
    }

    @Test
    void outerTransactionDoesNotRepopulateAKeyEvictedByCommittedInnerTransaction() {
        Cache users = manager.getCache("users");
        var transactionManager = new LocalTransactionManager();
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            users.put("41:user:alice", "stale-replica-read");
            inner.executeWithoutResult(innerStatus -> users.evict("41:user:alice"));
        });

        assertThat(users.get("41:user:alice")).isNull();
    }

    @Test
    void invalidationDuringATransactionalReadPreventsStaleCachePublication() {
        Cache users = manager.getCache("users");
        var transactionManager = new LocalTransactionManager();
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            assertThat(users.get("41:user:alice")).isNull();
            inner.executeWithoutResult(innerStatus -> users.evict("41:user:alice"));
            users.put("41:user:alice", "stale-replica-read");
        });
        assertThat(users.get("41:user:alice")).isNull();

        outer.executeWithoutResult(status -> assertThat(users.get("41:user:bob", () -> {
            inner.executeWithoutResult(innerStatus -> users.evict("41:user:bob"));
            return "stale-replica-read";
        })).isEqualTo("stale-replica-read"));
        assertThat(users.get("41:user:bob")).isNull();
    }

    @Test
    void concurrentEvictionCannotBeOverwrittenByAnEarlierTransactionLoad() throws Exception {
        Cache users = manager.getCache("users");
        RMapCache<String, Object> l2 = manager.l2("users");
        String key = "41:user:alice";
        String region = properties.getKeyPrefix() + ":users";
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        CountDownLatch evictionStarted = new CountDownLatch(1);
        CountDownLatch removeEntered = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            writeStarted.countDown();
            if (!allowWrite.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("write was not released");
            }
            data.get(region).put(key, call.getArgument(1));
            ttls.put(region + ":" + key, call.getArgument(2));
            return true;
        }).when(l2).fastPut(org.mockito.ArgumentMatchers.eq(key), any(), anyLong(), any());
        org.mockito.Mockito.doAnswer(call -> {
            removeEntered.countDown();
            return data.get(region).remove(key) == null ? 0L : 1L;
        }).when(l2).fastRemove(any(String[].class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> {
                MDC.put("tenantId", "41");
                try {
                    new TransactionTemplate(new LocalTransactionManager()).executeWithoutResult(status ->
                            users.put(key, "stale-replica-read"));
                } finally {
                    MDC.clear();
                }
            });
            assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> eviction = executor.submit(() -> {
                MDC.put("tenantId", "41");
                try {
                    evictionStarted.countDown();
                    users.evict(key);
                } finally {
                    MDC.clear();
                }
            });
            assertThat(evictionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            removeEntered.await(200, TimeUnit.MILLISECONDS);
            allowWrite.countDown();
            write.get(5, TimeUnit.SECONDS);
            eviction.get(5, TimeUnit.SECONDS);
            assertThat(users.get(key)).isNull();
        } finally {
            allowWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void l2HitBackfillsL1OnlyWhenRemainingLifetimeIsKnown() {
        Cache users = manager.getCache("users");
        users.put("41:user:carol", "shared");
        RMapCache<String, Object> l2 = manager.l2("users");
        when(l2.remainTimeToLive("41:user:carol")).thenReturn(-1L);
        manager.applyRemotePut("users", List.of("41:user:carol"));

        assertThat(users.get("41:user:carol")).isNull();
        assertThat(l1Peek(users, "41:user:carol")).isNull();

        when(l2.remainTimeToLive("41:user:carol")).thenReturn(60000L);
        Object refilled = users.get("41:user:carol", () -> null);
        assertThat(refilled).isEqualTo("shared");
        assertThat(l1Peek(users, "41:user:carol")).isNotNull();
    }

    @Test
    void remoteInvalidationDuringL2LookupDoesNotRefillStaleL1() {
        Cache users = manager.getCache("users");
        users.put("41:user:carol", "old");
        manager.applyRemotePut("users", List.of("41:user:carol"));
        RMapCache<String, Object> l2 = manager.l2("users");
        when(l2.remainTimeToLive("41:user:carol")).thenAnswer(call -> {
            manager.applyRemotePut("users", List.of("41:user:carol"));
            return 60000L;
        });

        assertThat(users.get("41:user:carol")).isNull();
        assertThat(l1Peek(users, "41:user:carol")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutatingInputOrReturnedValuesDoesNotChangeTheL1Snapshot() {
        Cache users = manager.getCache("users");
        var value = new LinkedHashMap<String, Object>();
        value.put("name", "old");
        value.put("nested", new LinkedHashMap<>(Map.of("state", "old")));
        users.put("41:user:alice", value);
        value.put("name", "mutated-input");
        ((Map<String, Object>) value.get("nested")).put("state", "mutated-input");

        Map<String, Object> first = (Map<String, Object>) users.get("41:user:alice").get();
        assertThat(first).containsEntry("name", "old");
        assertThat((Map<String, Object>) first.get("nested")).containsEntry("state", "old");
        first.put("name", "mutated-return");
        ((Map<String, Object>) first.get("nested")).put("state", "mutated-return");
        Map<String, Object> second = (Map<String, Object>) users.get("41:user:alice").get();
        assertThat(second).containsEntry("name", "old");
        assertThat((Map<String, Object>) second.get("nested")).containsEntry("state", "old");
    }

    @Test
    void mutableEntitySnapshotPreservesInheritedFieldsAndIsIsolated() {
        Cache users = manager.getCache("users");
        MutableEntity original = new MutableEntity();
        original.setId(7L);
        original.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        original.setName("old");
        users.put("41:user:alice", original);
        original.setName("changed");

        MutableEntity first = users.get("41:user:alice", MutableEntity.class);
        assertThat(first.getId()).isEqualTo(7L);
        assertThat(first.getCreatedAt()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(first.getName()).isEqualTo("old");
        first.setName("modified-return");
        assertThat(users.get("41:user:alice", MutableEntity.class).getName()).isEqualTo("old");
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentCacheMissesDoNotShareTheMutableLoaderResult() throws Exception {
        Cache users = manager.getCache("users");
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Map<String, Object>> request = () -> {
                MDC.put("tenantId", "41");
                try {
                    return users.get("41:user:alice", () -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("loader was not released");
                        }
                        Map<String, Object> loaded = new LinkedHashMap<>();
                        loaded.put("name", "old");
                        return loaded;
                    });
                } finally {
                    MDC.clear();
                }
            };
            Future<Map<String, Object>> firstFuture = executor.submit(request);
            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Map<String, Object>> secondFuture = executor.submit(request);
            releaseLoader.countDown();
            Map<String, Object> first = firstFuture.get(5, TimeUnit.SECONDS);
            Map<String, Object> second = secondFuture.get(5, TimeUnit.SECONDS);
            assertThat(loads).hasValue(1);
            assertThat(first).isNotSameAs(second);
            first.put("name", "changed");
            assertThat(second).containsEntry("name", "old");
            assertThat((Map<String, Object>) users.get("41:user:alice").get()).containsEntry("name", "old");
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uncopyableValueInvalidatesOldEntryInsteadOfCachingASharedReference() {
        Cache users = manager.getCache("users");
        users.put("41:user:alice", "old");
        users.put("41:user:alice", new Object());
        assertThat(users.get("41:user:alice")).isNull();
    }

    private static Object l1Peek(Cache cache, String key) {
        return ((com.google.common.cache.Cache<?, ?>) cache.getNativeCache()).getIfPresent(key);
    }

    @Test
    void l2BackfillNeverRenewsSharedLifetime() {
        Cache users = manager.getCache("users");
        RMapCache<String, Object> l2 = manager.l2("users");
        users.put("41:user:dave", "shared");
        manager.applyRemotePut("users", List.of("41:user:dave"));
        org.mockito.Mockito.clearInvocations(l2);

        Object hit = users.get("41:user:dave", () -> null);

        assertThat(hit).isEqualTo("shared");
        org.mockito.Mockito.verify(l2, org.mockito.Mockito.never())
                .fastPut(anyString(), any(), anyLong(), any());
        org.mockito.Mockito.verify(l2, org.mockito.Mockito.never()).put(anyString(), any());
    }

    @Test
    void commitUsesCapturedTenantAndL2LookupFailureFallsBack() {
        TransactionTemplate transaction = new TransactionTemplate(new LocalTransactionManager());
        transaction.executeWithoutResult(status -> {
            repository.update("alice", "new");
            MDC.put("tenantId", "42");
        });
        MDC.put("tenantId", "41");
        assertThat(repository.find("alice", true)).isEqualTo("new");
        manager.applyRemotePut("users", List.of("41:user:alice"));
        doThrow(new IllegalStateException("redis down")).when(manager.l2("users")).get(anyString());
        assertThat(repository.find("alice", true)).isEqualTo("alice");
    }

    @Test
    void managerOwnsSchedulerLifecycle() {
        manager.destroy();
        assertThat(manager.scheduler().isShutdown()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class CacheConfiguration {
    }

    @CacheConfig(cacheNames = "users")
    static class ExampleRepository {
        private final AtomicInteger calls;

        ExampleRepository(AtomicInteger calls) {
            this.calls = calls;
        }

        @Cacheable(key = "T(org.slf4j.MDC).get('tenantId') + ':user:' + #p0", condition = "#p1", unless = "#result == null")
        public String find(String name, boolean enabled) {
            return load(name);
        }

        @Cacheable(key = "T(org.slf4j.MDC).get('tenantId') + ':user:' + #p0", sync = true)
        public String sync(String name) {
            return load(name);
        }

        @Cacheable(key = "'41:' + #p0", sync = true, unless = "#result == null")
        public String invalidSync(String name) {
            return load(name);
        }

        @Caching(put = @CachePut(key = "T(org.slf4j.MDC).get('tenantId') + ':user:' + #p0"),
                evict = @CacheEvict(cacheNames = "search", allEntries = true))
        public String update(String name, String value) {
            calls.incrementAndGet();
            return value;
        }

        @CacheEvict(key = "T(org.slf4j.MDC).get('tenantId') + ':user:' + #p0")
        public void delete(String name) {
        }

        @CacheEvict(key = "T(org.slf4j.MDC).get('tenantId') + ':user:' + #p0", beforeInvocation = true)
        public void deleteBefore(String name) {
            throw new IllegalStateException("business failure");
        }

        public String selfFind(String name) {
            return find(name, true);
        }

        private String load(String name) {
            calls.incrementAndGet();
            return "missing".equals(name) ? null : name;
        }
    }

    public static class EntityBase {
        private Long id;
        private java.time.Instant createdAt;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    }

    public static class MutableEntity extends EntityBase {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
