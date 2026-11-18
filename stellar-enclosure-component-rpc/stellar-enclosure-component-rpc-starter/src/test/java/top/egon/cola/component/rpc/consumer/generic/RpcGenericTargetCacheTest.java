package top.egon.cola.component.rpc.consumer.generic;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinition;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcGenericTargetCacheTest {

    private static final String SERVICE = "sample.Service";
    private static final String METHOD = SERVICE + "/Echo";
    private static final String OTHER_METHOD = SERVICE + "/Other";
    private static final RpcServiceIdentity IDENTITY = new RpcServiceIdentity(SERVICE, "test", "1.0.0");

    @Test
    void sharesOneStrategyAndDescriptorForConcurrentNormalizedTarget() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RpcReferenceDefinitionResolver resolver = resolver();
        RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver, definition -> {
            creations.incrementAndGet();
            return strategy(definition.target(), closes);
        }, 8, Duration.ofSeconds(1), () -> 1000);
        RpcGenericInvocation invocation = new RpcGenericInvocation(" binding-one ", " sample.Service ",
                " test ", " 1.0.0 ", " sample.Service/Echo ", new byte[]{1, 2, 3}, 1000);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RpcGenericTargetCache.Entry>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return cache.resolve(invocation);
                    })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RpcGenericTargetCache.Entry shared = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<RpcGenericTargetCache.Entry> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(shared);
            }
            assertThat(shared.inUse()).isEqualTo(8);
            assertThat(shared.method().getFullMethodName()).isEqualTo(METHOD);
            for (Future<RpcGenericTargetCache.Entry> future : futures) {
                future.get(5, TimeUnit.SECONDS).release();
            }
            assertThat(shared.inUse()).isZero();
            assertThat(creations).hasValue(1);
            assertThat(cache.size()).isOne();
            verify(resolver, times(8)).resolve("binding-one", IDENTITY, Set.of(METHOD));
        } finally {
            start.countDown();
            executor.shutdownNow();
            cache.close();
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void splitsLogicalIdentityAndExactMethodButDoesNotKeyByDeadlineOrPayload() {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition -> {
            creations.incrementAndGet();
            return strategy(definition.target(), closes);
        }, 8, Duration.ofSeconds(1), () -> 1000)) {
            RpcGenericTargetCache.Entry first = cache.resolve(invocation("one"));
            RpcGenericTargetCache.Entry shorterDeadline = cache.resolve(new RpcGenericInvocation(
                    "binding-one", SERVICE, "test", "1.0.0", METHOD, new byte[]{9}, 25));
            assertThat(shorterDeadline).isSameAs(first);
            assertThat(shorterDeadline.method()).isSameAs(first.method());
            List<RpcGenericTargetCache.Entry> split = List.of(
                    cache.resolve(new RpcGenericInvocation("binding-one", SERVICE, "test", "1.0.0",
                            OTHER_METHOD, new byte[0], 1000)),
                    cache.resolve(new RpcGenericInvocation("binding-one", SERVICE, "other", "1.0.0",
                            METHOD, new byte[0], 1000)),
                    cache.resolve(new RpcGenericInvocation("binding-one", SERVICE, "test", "2.0.0",
                            METHOD, new byte[0], 1000)),
                    cache.resolve(invocation("two")));
            assertThat(split).doesNotContain(first);
            assertThat(split.getFirst().method().getFullMethodName()).isEqualTo(OTHER_METHOD);
            assertThat(creations).hasValue(5);
            assertThat(cache.size()).isEqualTo(5);
            first.release();
            shorterDeadline.release();
            split.forEach(RpcGenericTargetCache.Entry::release);
        }
        assertThat(closes).hasValue(5);
    }

    @Test
    void splitsOnEveryTrustedTargetFieldIncludingVersionAndApprovedMethods() {
        RpcServiceTargetVO original = target("binding-one");
        List<RpcServiceTargetVO> targets = List.of(original,
                new RpcServiceTargetVO("binding-one", 2, "apps", "rpc-frontdoor", 9090, original.methods()),
                new RpcServiceTargetVO("binding-one", 1, "apps", "rpc-frontdoor", 9090, Set.of(METHOD)),
                new RpcServiceTargetVO("binding-one", 1, "other-apps", "rpc-frontdoor", 9090, original.methods()),
                new RpcServiceTargetVO("binding-one", 1, "apps", "other-frontdoor", 9090, original.methods()),
                new RpcServiceTargetVO("binding-one", 1, "apps", "rpc-frontdoor", 9091, original.methods()));
        AtomicReference<RpcServiceTargetVO> current = new AtomicReference<>(original);
        RpcReferenceDefinitionResolver resolver = mock(RpcReferenceDefinitionResolver.class);
        when(resolver.resolve("binding-one", IDENTITY, Set.of(METHOD)))
                .thenAnswer(ignored -> new RpcReferenceDefinition(current.get(), IDENTITY, Map.of()));
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver, definition -> {
            creations.incrementAndGet();
            return strategy(definition.target(), closes);
        }, 8, Duration.ofSeconds(1), () -> 1000)) {
            List<RpcGenericTargetCache.Entry> entries = new ArrayList<>();
            for (RpcServiceTargetVO target : targets) {
                current.set(target);
                RpcGenericTargetCache.Entry entry = cache.resolve(invocation("one"));
                assertThat(entry.strategy().target()).isEqualTo(target);
                assertThat(entries).doesNotContain(entry);
                entries.add(entry);
                entry.release();
            }
            current.set(original);
            RpcGenericTargetCache.Entry reused = cache.resolve(invocation("one"));
            assertThat(reused).isSameAs(entries.getFirst());
            reused.release();
            assertThat(creations).hasValue(targets.size());
        }
        assertThat(closes).hasValue(targets.size());
    }

    @Test
    void revalidatesTheTrustedBindingBeforeServingACacheHit() {
        RpcReferenceDefinitionResolver resolver = mock(RpcReferenceDefinitionResolver.class);
        when(resolver.resolve("binding-one", IDENTITY, Set.of(METHOD)))
                .thenReturn(new RpcReferenceDefinition(target("binding-one"), IDENTITY, Map.of()))
                .thenThrow(new IllegalArgumentException("binding is no longer approved"));
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver, definition -> {
            creations.incrementAndGet();
            return strategy(definition.target(), closes);
        }, 4, Duration.ofSeconds(1), () -> 1000)) {
            RpcGenericTargetCache.Entry first = cache.resolve(invocation("one"));
            first.release();
            assertThatThrownBy(() -> cache.resolve(invocation("one")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("binding is no longer approved");
            assertThat(first.inUse()).isZero();
            assertThat(creations).hasValue(1);
            verify(resolver, times(2)).resolve("binding-one", IDENTITY, Set.of(METHOD));
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void evictsLeastRecentlyUsedIdleEntryAtBound() {
        Map<String, AtomicInteger> closes = new LinkedHashMap<>();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition -> strategy(
                definition.target(), closes.computeIfAbsent(definition.target().bindingId(), ignored -> new AtomicInteger())),
                2, Duration.ofSeconds(1), () -> 1000)) {
            RpcGenericTargetCache.Entry first = cache.resolve(invocation("one"));
            first.release();
            cache.resolve(invocation("two")).release();
            RpcGenericTargetCache.Entry touched = cache.resolve(invocation("one"));
            assertThat(touched).isSameAs(first);
            touched.release();
            cache.resolve(invocation("three")).release();
            assertThat(cache.size()).isEqualTo(2);
            assertThat(closes.get("binding-one")).hasValue(0);
            assertThat(closes.get("binding-two")).hasValue(1);
            assertThat(closes.get("binding-three")).hasValue(0);
        }
        assertThat(closes.values()).allSatisfy(count -> assertThat(count).hasValue(1));
    }

    @Test
    void activeEntriesDoNotExpireAndIdleTimeoutStartsAtTheLastRelease() {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(),
                definition -> strategy(definition.target(), closes), 4, Duration.ofSeconds(1), now::get)) {
            RpcGenericTargetCache.Entry active = cache.resolve(invocation("idle"));
            now.set(2100);
            cache.evictIdle();
            assertThat(cache.size()).isOne();
            assertThat(closes).hasValue(0);
            active.release();
            now.set(3099);
            cache.evictIdle();
            assertThat(closes).hasValue(0);
            now.set(3100);
            cache.evictIdle();
            cache.evictIdle();
            assertThat(cache.size()).isZero();
            assertThat(closes).hasValue(1);
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void refusesExpansionWhileAllEntriesAreBorrowed() {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition -> {
            creations.incrementAndGet();
            return strategy(definition.target(), closes);
        }, 2, Duration.ofSeconds(1), () -> 1000)) {
            RpcGenericTargetCache.Entry first = cache.resolve(invocation("one"));
            RpcGenericTargetCache.Entry second = cache.resolve(invocation("two"));
            assertThatThrownBy(() -> cache.resolve(invocation("three")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active-entry limit");
            assertThat(cache.size()).isEqualTo(2);
            assertThat(creations).hasValue(2);
            assertThat(closes).hasValue(0);
            first.release();
            cache.resolve(invocation("three")).release();
            assertThat(cache.size()).isEqualTo(2);
            assertThat(creations).hasValue(3);
            assertThat(closes).hasValue(1);
            second.release();
        }
        assertThat(closes).hasValue(3);
    }

    @Test
    void shutdownDefersAnchorCloseUntilTheLastConcurrentRelease() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        RpcReferenceDefinitionResolver resolver = resolver();
        RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver,
                definition -> strategy(definition.target(), closes), 4, Duration.ofSeconds(1), () -> 1000);
        List<RpcGenericTargetCache.Entry> borrowers = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> cache.resolve(invocation("one"))).toList();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            cache.close();
            cache.close();
            assertThat(cache.size()).isZero();
            assertThat(closes).hasValue(0);
            assertThatThrownBy(() -> cache.resolve(invocation("one")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            List<Future<?>> releases = new ArrayList<>();
            for (RpcGenericTargetCache.Entry entry : borrowers) {
                releases.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    entry.release();
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> release : releases) {
                release.get(5, TimeUnit.SECONDS);
            }
            assertThat(borrowers.getFirst().inUse()).isZero();
            borrowers.getFirst().release();
            cache.close();
            assertThat(closes).hasValue(1);
            verify(resolver, times(8)).resolve("binding-one", IDENTITY, Set.of(METHOD));
        } finally {
            start.countDown();
            executor.shutdownNow();
            borrowers.forEach(RpcGenericTargetCache.Entry::release);
            cache.close();
        }
    }

    @Test
    void doesNotCacheFailedStrategyCreation() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("creation failed");
            }
            return strategy(definition.target(), closes);
        }, 4, Duration.ofSeconds(1), () -> 1000)) {
            assertThatThrownBy(() -> cache.resolve(invocation("failed")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("creation failed");
            assertThat(cache.size()).isZero();
            cache.resolve(invocation("failed")).release();
            assertThat(attempts).hasValue(2);
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void rejectsAndClosesAStrategyThatDoesNotMatchTheTrustedTarget() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rejectedCloses = new AtomicInteger();
        AtomicInteger acceptedCloses = new AtomicInteger();
        try (RpcGenericTargetCache cache = new RpcGenericTargetCache(resolver(), definition ->
                attempts.getAndIncrement() == 0
                        ? strategy(target("binding-other"), rejectedCloses)
                        : strategy(definition.target(), acceptedCloses),
                4, Duration.ofSeconds(1), () -> 1000)) {
            assertThatThrownBy(() -> cache.resolve(invocation("one")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trusted binding");
            assertThat(cache.size()).isZero();
            assertThat(rejectedCloses).hasValue(1);
            cache.resolve(invocation("one")).release();
            assertThat(attempts).hasValue(2);
        }
        assertThat(rejectedCloses).hasValue(1);
        assertThat(acceptedCloses).hasValue(1);
    }

    private RpcGenericInvocation invocation(String suffix) {
        return new RpcGenericInvocation("binding-" + suffix, SERVICE, "test", "1.0.0",
                METHOD, new byte[]{1, 2, 3}, 1000);
    }

    private RpcReferenceDefinitionResolver resolver() {
        RpcReferenceDefinitionResolver resolver = mock(RpcReferenceDefinitionResolver.class);
        when(resolver.resolve(anyString(), any(RpcServiceIdentity.class), anySet()))
                .thenAnswer(call -> new RpcReferenceDefinition(target(call.getArgument(0)),
                        call.getArgument(1), Map.of()));
        return resolver;
    }

    private RpcServiceTargetVO target(String bindingId) {
        return new RpcServiceTargetVO(bindingId, 1, "apps", "rpc-frontdoor", 9090, Set.of(METHOD, OTHER_METHOD));
    }

    private RpcReferenceStrategy strategy(RpcServiceTargetVO target, AtomicInteger closes) {
        return new RpcReferenceStrategy() {
            @Override public RpcServiceTargetVO target() { return target; }
            @Override public String queryIdentity() { return target.bindingId() + "@" + target.version(); }
            @Override public long revision() { return target.version(); }
            @Override public RpcChannelLease acquire() {
                throw new AssertionError("target cache must not acquire a per-call channel lease");
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }
}
