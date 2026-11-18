package top.egon.cola.component.common.id.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMachineIdProviderTest {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration RENEW = Duration.ofMinutes(1);

    @Test
    void allocatesSequentialSlotsWithinSameEnvAndAppAndAdvancesRingCursor() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (RedisMachineIdProvider first = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "pod-1");
             RedisMachineIdProvider second = new RedisMachineIdProvider(
                     redis.client(), "prod", "order-service", LEASE, RENEW, "pod-2");
             RedisMachineIdProvider third = new RedisMachineIdProvider(
                     redis.client(), "prod", "order-service", LEASE, RENEW, "pod-3")) {

            assertThat(first.getMachineId()).isEqualTo(0L);
            assertThat(second.getMachineId()).isEqualTo(1L);
            assertThat(third.getMachineId()).isEqualTo(2L);
            // Calling getMachineId() repeatedly on the same instance is idempotent
            assertThat(first.getMachineId()).isEqualTo(0L);
            assertThat(redis.cursor(first.cursorKey())).isEqualTo(3);
        }
    }

    @Test
    void isolatesPoolsAcrossDifferentEnvAndAppScopes() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (RedisMachineIdProvider prodOrder = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "pod-a");
             RedisMachineIdProvider testOrder = new RedisMachineIdProvider(
                     redis.client(), "test", "order-service", LEASE, RENEW, "pod-b");
             RedisMachineIdProvider prodUser = new RedisMachineIdProvider(
                     redis.client(), "prod", "user-service", LEASE, RENEW, "pod-c")) {

            assertThat(prodOrder.getMachineId()).isEqualTo(0L);
            assertThat(testOrder.getMachineId()).isEqualTo(0L);
            assertThat(prodUser.getMachineId()).isEqualTo(0L);
        }
    }

    @Test
    void ringCursorAvoidsImmediateReuseOfRecentlyReleasedSlotDuringRollout() {
        FakeRedisCluster redis = new FakeRedisCluster();
        RedisMachineIdProvider oldPod = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "old-pod");
        assertThat(oldPod.getMachineId()).isEqualTo(0L);
        oldPod.close();
        assertThat(redis.slotOwner(oldPod.slotKey(0L))).isNull();

        // Even though slot 0 is now free, the next pod starts at cursor=1
        try (RedisMachineIdProvider canaryPod = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "canary-pod")) {
            assertThat(canaryPod.getMachineId()).isEqualTo(1L);
        }
    }

    @Test
    void renewExtendsTtlWhenOwnedAndSelfHealsWhenExpiredButUnclaimed() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (RedisMachineIdProvider provider = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "pod-1")) {
            long machineId = provider.getMachineId();
            assertThat(machineId).isEqualTo(0L);

            redis.advance(Duration.ofMinutes(2));
            assertThat(provider.renewOnce()).isEqualTo(1);
            assertThat(redis.remainingTtlMillis(provider.slotKey(0L))).isEqualTo(LEASE.toMillis());

            // Simulate transient network stall exceeding lease TTL while no other pod claimed slot 0
            redis.advance(LEASE.plusSeconds(10));
            assertThat(redis.slotOwner(provider.slotKey(0L))).isNull();

            // Heartbeat recovers the same slot in place
            assertThat(provider.renewOnce()).isEqualTo(2);
            assertThat(redis.slotOwner(provider.slotKey(0L))).isEqualTo("pod-1");
            assertThat(redis.remainingTtlMillis(provider.slotKey(0L))).isEqualTo(LEASE.toMillis());
        }
    }

    @Test
    void renewDetectsConflictAndCloseDoesNotDeleteSlotOwnedByAnotherInstance() {
        FakeRedisCluster redis = new FakeRedisCluster();
        RedisMachineIdProvider provider = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "pod-1");
        assertThat(provider.getMachineId()).isEqualTo(0L);

        // Simulate lease expiration and takeover by another instance
        redis.forceSlotOwner(provider.slotKey(0L), "pod-other", LEASE.toMillis());
        assertThat(provider.renewOnce()).isEqualTo(0);

        provider.close();
        assertThat(redis.slotOwner(provider.slotKey(0L))).isEqualTo("pod-other");
    }

    @Test
    void exhaustedPoolFailsFastWithCommonException() {
        FakeRedisCluster redis = new FakeRedisCluster();
        for (int slot = 0; slot < 1024; slot++) {
            redis.forceSlotOwner("egon:cola:id:{prod:order-service}:slot:" + slot, "holder-" + slot, LEASE.toMillis());
        }

        try (RedisMachineIdProvider candidate = new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, RENEW, "pod-1025")) {
            assertThatThrownBy(candidate::getMachineId)
                    .isInstanceOf(CommonException.class)
                    .hasMessageContaining("No available Snowflake machine-id slot (0..1023) in Redis for env=prod, app=order-service");
        }
    }

    @Test
    void redisTransportFailureOnAcquireWrapsInCommonException() {
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisException("Connection refused"));

        try (RedisMachineIdProvider provider = new RedisMachineIdProvider(
                client, "prod", "order-service", LEASE, RENEW, "pod-1")) {
            assertThatThrownBy(provider::getMachineId)
                    .isInstanceOf(CommonException.class)
                    .hasMessageContaining("Failed to allocate Snowflake machine-id from Redis for env=prod, app=order-service")
                    .hasCauseInstanceOf(RedisException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "prod:extra", "prod{1}", "prod}1", "prod env"})
    void rejectsInvalidEnvAndAppSegments(String invalid) {
        FakeRedisCluster redis = new FakeRedisCluster();
        assertThatThrownBy(() -> new RedisMachineIdProvider(
                redis.client(), invalid, "order-service", LEASE, RENEW))
                .isInstanceOf(CommonException.class)
                .hasMessageContaining("egon.cola.component.id.redis.env");

        assertThatThrownBy(() -> new RedisMachineIdProvider(
                redis.client(), "prod", invalid, LEASE, RENEW))
                .isInstanceOf(CommonException.class)
                .hasMessageContaining("egon.cola.component.id.redis.app");
    }

    @Test
    void rejectsInvalidLeaseAndRenewDurations() {
        FakeRedisCluster redis = new FakeRedisCluster();
        assertThatThrownBy(() -> new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", Duration.ZERO, RENEW))
                .isInstanceOf(CommonException.class)
                .hasMessageContaining("egon.cola.component.id.redis.lease-duration must be positive");

        assertThatThrownBy(() -> new RedisMachineIdProvider(
                redis.client(), "prod", "order-service", LEASE, Duration.ofMinutes(5)))
                .isInstanceOf(CommonException.class)
                .hasMessageContaining("egon.cola.component.id.redis.renew-interval must be smaller than lease-duration");
    }

    /**
     * In-memory evaluator for the three Redis Lua scripts (`acquire`, `renew`, `release`).
     */
    static final class FakeRedisCluster {

        private final Map<String, Integer> cursors = new HashMap<>();
        private final Map<String, SlotEntry> slots = new HashMap<>();
        private long nowMillis = 1_000_000L;

        RedissonClient client() {
            RedissonClient client = mock(RedissonClient.class);
            RScript script = mock(RScript.class);
            when(client.getScript(any(Codec.class))).thenReturn(script);
            when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        Object[] raw = invocation.getArguments();
                        return evaluate(
                                invocation.getArgument(1),
                                invocation.getArgument(3),
                                Arrays.copyOfRange(raw, 4, raw.length));
                    });
            return client;
        }

        private synchronized Object evaluate(String script, List<Object> keys, Object[] args) {
            evictExpired();
            String key = keys.getFirst().toString();
            if (script.contains("common-id:machine-lease:acquire")) {
                String prefix = args[0].toString();
                String instanceId = args[1].toString();
                long leaseMillis = Long.parseLong(args[2].toString());
                int start = cursors.getOrDefault(key, 0);
                if (start < 0 || start >= 1024) {
                    start = 0;
                }
                for (int i = 0; i < 1024; i++) {
                    int candidate = (start + i) % 1024;
                    String slotKey = prefix + candidate;
                    if (!slots.containsKey(slotKey)) {
                        slots.put(slotKey, new SlotEntry(instanceId, nowMillis + leaseMillis));
                        cursors.put(key, (candidate + 1) % 1024);
                        return (long) candidate;
                    }
                }
                return -1L;
            }
            if (script.contains("common-id:machine-lease:renew")) {
                String instanceId = args[0].toString();
                long leaseMillis = Long.parseLong(args[1].toString());
                SlotEntry current = slots.get(key);
                if (current != null && current.instanceId().equals(instanceId)) {
                    slots.put(key, new SlotEntry(instanceId, nowMillis + leaseMillis));
                    return 1L;
                }
                if (current == null) {
                    slots.put(key, new SlotEntry(instanceId, nowMillis + leaseMillis));
                    return 2L;
                }
                return 0L;
            }
            if (script.contains("common-id:machine-lease:release")) {
                String instanceId = args[0].toString();
                SlotEntry current = slots.get(key);
                if (current != null && current.instanceId().equals(instanceId)) {
                    slots.remove(key);
                    return 1L;
                }
                return 0L;
            }
            throw new AssertionError("Unexpected Lua script: " + script);
        }

        synchronized void advance(Duration duration) {
            nowMillis += duration.toMillis();
            evictExpired();
        }

        synchronized void forceSlotOwner(String slotKey, String instanceId, long ttlMillis) {
            slots.put(slotKey, new SlotEntry(instanceId, nowMillis + ttlMillis));
        }

        synchronized int cursor(String cursorKey) {
            return cursors.getOrDefault(cursorKey, 0);
        }

        synchronized String slotOwner(String slotKey) {
            evictExpired();
            SlotEntry entry = slots.get(slotKey);
            return entry == null ? null : entry.instanceId();
        }

        synchronized long remainingTtlMillis(String slotKey) {
            evictExpired();
            SlotEntry entry = slots.get(slotKey);
            return entry == null ? -2L : entry.expireAtMillis() - nowMillis;
        }

        private void evictExpired() {
            slots.entrySet().removeIf(entry -> entry.getValue().expireAtMillis() <= nowMillis);
        }

        private record SlotEntry(String instanceId, long expireAtMillis) {
        }
    }
}
