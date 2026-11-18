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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void acquisitionDeclaresEveryCandidateSlotAsAnExplicitSameScopeEvalKey() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (RedisMachineIdProvider provider = provider(redis, "pod-keys")) {
            assertThat(provider.getMachineId()).isEqualTo(0);
            assertThat(redis.lastAcquisitionKeys).hasSize(RedisMachineIdProvider.SLOT_COUNT + 1);
            assertThat(redis.lastAcquisitionKeys.getFirst()).isEqualTo(provider.cursorKey());
            for (int slot = 0; slot < RedisMachineIdProvider.SLOT_COUNT; slot++) {
                assertThat(redis.lastAcquisitionKeys.get(slot + 1)).isEqualTo(provider.slotKey(slot));
            }
            assertThat(redis.lastAcquisitionKeys).doesNotHaveDuplicates()
                    .allSatisfy(key -> assertThat(key).contains("{prod:order-service}"));
        }
    }

    @Test
    void allocatesSequentialSlotsWithinSameEnvAndAppAndAdvancesRingCursor() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var first = provider(redis, "pod-1"); var second = provider(redis, "pod-2");
             var third = provider(redis, "pod-3")) {
            assertThat(first.getMachineId()).isZero();
            assertThat(second.getMachineId()).isEqualTo(1);
            assertThat(third.getMachineId()).isEqualTo(2);
            assertThat(first.getMachineId()).isZero();
            assertThat(redis.cursor(first.cursorKey())).isEqualTo(3);
        }
    }

    @Test
    void isolatesPoolsAcrossDifferentEnvAndAppScopes() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var prod = provider(redis, "prod", "order-service", "pod-a");
             var test = provider(redis, "test", "order-service", "pod-b");
             var users = provider(redis, "prod", "user-service", "pod-c")) {
            assertThat(prod.getMachineId()).isZero();
            assertThat(test.getMachineId()).isZero();
            assertThat(users.getMachineId()).isZero();
        }
    }

    @Test
    void closeLeavesAPersistentWatermarkAndRingCursorAvoidsImmediateReuse() {
        FakeRedisCluster redis = new FakeRedisCluster();
        var old = provider(redis, "old");
        assertThat(old.getMachineId()).isZero();
        long upper = redis.slot(old.slotKey(0)).upper();
        old.close();
        assertThat(redis.values.get(old.slotKey(0))).isEqualTo("R|" + upper);
        assertThat(redis.lastReleaseKeys).containsExactly(old.cursorKey(), old.slotKey(0));
        try (var canary = provider(redis, "canary")) { assertThat(canary.getMachineId()).isEqualTo(1); }
        assertThatThrownBy(old::getMachineId).isInstanceOf(CommonException.class);
    }

    @Test
    void releasedSlotRequiresRedisTimeStrictlyBeyondItsPreviousUpperAndIdTimestampAboveTheWatermark() {
        FakeRedisCluster redis = new FakeRedisCluster();
        long oldUpper = redis.nowMillis + 5;
        redis.fillActive("prod", "order-service");
        redis.values.put(redis.slotKey(0), "R|" + oldUpper);
        try (var early = provider(redis, "early")) {
            assertThatThrownBy(early::getMachineId).isInstanceOf(CommonException.class);
        }
        redis.advance(Duration.ofMillis(5));
        try (var equal = provider(redis, "equal")) {
            assertThatThrownBy(equal::getMachineId).isInstanceOf(CommonException.class);
        }
        redis.advance(Duration.ofMillis(1));
        try (var later = provider(redis, "later")) {
            assertThat(later.getMachineId()).isZero();
            assertThat(redis.slot(later.slotKey(0)).floor()).isEqualTo(oldUpper + 1);
            assertThat(later.issue(() -> 42, ignored -> oldUpper + 1)).isEqualTo(42);
            assertThatThrownBy(() -> later.issue(() -> 43, ignored -> oldUpper)).isInstanceOf(CommonException.class);
            assertThatThrownBy(later::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
    }

    @Test
    void renewalExtendsOnlyAStillConfirmedLeaseAndNeverExpiresPersistentKeys() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "owner")) {
            provider.getMachineId();
            redis.advance(Duration.ofMinutes(1));
            assertThat(provider.renewOnce()).isEqualTo(1);
            assertThat(redis.slot(provider.slotKey(0)).expiry()).isEqualTo(redis.nowMillis + LEASE.toMillis());
            assertThat(redis.lastRenewKeys).containsExactly(provider.cursorKey(), provider.slotKey(0));
            redis.advance(Duration.ofMinutes(4));
            provider.assertIssuanceAvailable();
            redis.advance(Duration.ofMinutes(1));
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
            assertThat(provider.renewOnce()).isEqualTo(-1);
            assertThat(redis.slot(provider.slotKey(0)).owner()).isEqualTo("owner");
        }
    }

    @Test
    void missingSlotIsTerminalAndCannotSelfHealEvenIfARecordLaterReappears() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "owner")) {
            provider.getMachineId();
            redis.values.remove(provider.slotKey(0));
            assertThat(provider.renewOnce()).isZero();
            assertThat(redis.values).doesNotContainKey(provider.slotKey(0));
            redis.values.put(provider.slotKey(0), "A|owner|" + (redis.nowMillis + LEASE.toMillis())
                    + "|0|" + (redis.nowMillis + LEASE.toMillis()));
            assertThat(provider.renewOnce()).isEqualTo(-1);
            assertThatThrownBy(provider::getMachineId).isInstanceOf(CommonException.class);
            AtomicInteger generators = new AtomicInteger();
            assertThatThrownBy(() -> provider.issue(generators::incrementAndGet, ignored -> redis.nowMillis))
                    .isInstanceOf(CommonException.class);
            assertThat(generators).hasValue(0);
        }
    }

    @Test
    void expiredActiveOwnersRemainFrozenForNewProcesses() {
        FakeRedisCluster redis = new FakeRedisCluster();
        redis.fillActive("prod", "order-service");
        redis.advance(LEASE.plusSeconds(1));
        try (var next = provider(redis, "next")) {
            assertThatThrownBy(next::getMachineId).isInstanceOf(CommonException.class)
                    .hasMessageContaining("No available Snowflake machine-id slot");
            assertThat(redis.slot(next.slotKey(0)).state()).isEqualTo("A");
        }
    }

    @Test
    void ownerConflictStopsIssuanceAndCloseCannotOverwriteTheOtherOwner() {
        FakeRedisCluster redis = new FakeRedisCluster();
        var provider = provider(redis, "owner");
        provider.getMachineId();
        redis.forceOwner(provider.slotKey(0), "other");
        assertThat(provider.renewOnce()).isZero();
        assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        provider.close();
        assertThat(redis.slot(provider.slotKey(0)).owner()).isEqualTo("other");
    }

    @Test
    void changedRedisAuthorityFreezesExistingAndNewOwnersWithoutRewritingThePool() {
        for (String authority : List.of("b".repeat(40) + ":2", "a".repeat(40) + ":2")) {
            FakeRedisCluster redis = new FakeRedisCluster();
            var existing = provider(redis, "existing");
            existing.getMachineId();
            Map<String, String> before = Map.copyOf(redis.values);
            redis.runId = authority;
            assertThat(existing.renewOnce()).isZero();
            assertThatThrownBy(existing::assertIssuanceAvailable).isInstanceOf(CommonException.class);
            existing.close();
            try (var newcomer = provider(redis, "new")) {
                assertThatThrownBy(newcomer::getMachineId).isInstanceOf(CommonException.class);
            }
            assertThat(redis.values).isEqualTo(before);
        }
    }

    @Test
    void missingCursorOrAnySlotAndCorruptRecordsDenyBeforeAnyWrite() {
        for (String damage : List.of("cursor", "slot", "corrupt")) {
            FakeRedisCluster redis = new FakeRedisCluster();
            if (damage.equals("cursor")) { redis.values.remove(redis.cursorKey()); }
            if (damage.equals("slot")) { redis.values.remove(redis.slotKey(1023)); }
            if (damage.equals("corrupt")) { redis.values.put(redis.slotKey(1023), "untrusted-history"); }
            Map<String, String> before = Map.copyOf(redis.values);
            try (var provider = provider(redis, "owner")) {
                assertThatThrownBy(provider::getMachineId).isInstanceOf(CommonException.class);
            }
            assertThat(redis.values).isEqualTo(before);
        }
    }

    @Test
    void aLateSuccessfulRenewalReplyCannotReviveTheOldLocalLease() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "owner")) {
            provider.getMachineId();
            redis.advance(Duration.ofMinutes(1));
            redis.afterRenew = () -> redis.advance(Duration.ofMinutes(4).plusMillis(1));
            assertThat(provider.renewOnce()).isZero();
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
            assertThat(provider.renewOnce()).isEqualTo(-1);
        }
    }

    @Test
    void unchangedOrBackwardsRedisTimeCannotExtendTheOriginalMonotonicBound() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "owner")) {
            provider.getMachineId();
            redis.advanceLocal(Duration.ofMinutes(1));
            assertThat(provider.renewOnce()).isEqualTo(1);
            redis.advanceLocal(Duration.ofMinutes(4));
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
        FakeRedisCluster backwards = new FakeRedisCluster();
        try (var provider = provider(backwards, "owner")) {
            provider.getMachineId();
            backwards.nowMillis--;
            assertThat(provider.renewOnce()).isZero();
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
    }

    @Test
    void elapsedAcquireReplyIsRejectedAndNeverStartsIssuance() {
        FakeRedisCluster redis = new FakeRedisCluster();
        redis.afterAcquire = () -> redis.advanceLocal(LEASE.plusMillis(1));
        try (var provider = provider(redis, "owner")) {
            assertThatThrownBy(provider::getMachineId).isInstanceOf(CommonException.class);
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
    }

    @Test
    void issuanceRechecksTheLeaseAfterGeneratorWaitAndChecksTheActualDecodedTimestamp() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "paused")) {
            provider.getMachineId();
            assertThatThrownBy(() -> provider.issue(() -> {
                redis.advanceLocal(LEASE.plusMillis(1));
                return 42;
            }, ignored -> redis.wallMillis)).isInstanceOf(CommonException.class);
            assertThat(provider.renewOnce()).isEqualTo(-1);
        }
        FakeRedisCluster future = new FakeRedisCluster();
        try (var provider = provider(future, "future")) {
            provider.getMachineId();
            assertThatThrownBy(() -> provider.issue(() -> 42,
                    ignored -> future.slot(provider.slotKey(0)).upper() + 1)).isInstanceOf(CommonException.class);
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
    }

    @Test
    void readinessRejectsWallClockOutsideTheGrantedRangeBeforeCallingTheGenerator() {
        FakeRedisCluster redis = new FakeRedisCluster();
        try (var provider = provider(redis, "owner")) {
            provider.getMachineId();
            redis.wallMillis = redis.slot(provider.slotKey(0)).upper() + 1;
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
            AtomicInteger generators = new AtomicInteger();
            assertThatThrownBy(() -> provider.issue(generators::incrementAndGet, ignored -> 0))
                    .isInstanceOf(CommonException.class);
            assertThat(generators).hasValue(0);
        }
    }

    @Test
    void closeDrainsAnInFlightGeneratorBeforeCasReleaseAndPreventsFurtherIssuance() throws Exception {
        FakeRedisCluster redis = new FakeRedisCluster();
        var provider = provider(redis, "owner");
        provider.getMachineId();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closing = new CountDownLatch(1);
        AtomicBoolean inside = new AtomicBoolean();
        redis.beforeRelease = () -> assertThat(inside).isFalse();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var issued = workers.submit(() -> provider.issue(() -> {
                inside.set(true);
                entered.countDown();
                try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                inside.set(false);
                return 42;
            }, ignored -> redis.wallMillis));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var closed = workers.submit(() -> { closing.countDown(); provider.close(); });
            assertThat(closing.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(closed.isDone()).isFalse();
            release.countDown();
            assertThat(issued.get(3, TimeUnit.SECONDS)).isEqualTo(42);
            closed.get(3, TimeUnit.SECONDS);
            assertThat(redis.values.get(provider.slotKey(0))).startsWith("R|");
            assertThatThrownBy(() -> provider.issue(() -> 43, ignored -> redis.wallMillis))
                    .isInstanceOf(CommonException.class);
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    void redisTransportFailureOnAcquireWrapsInCommonExceptionAndRenewalFailureIsTerminal() {
        FakeRedisCluster redis = new FakeRedisCluster();
        redis.fail = true;
        try (var provider = provider(redis, "acquire")) {
            assertThatThrownBy(provider::getMachineId).isInstanceOf(CommonException.class)
                    .hasMessageContaining("Failed to allocate Snowflake machine-id from Redis")
                    .hasCauseInstanceOf(RedisException.class);
        }
        FakeRedisCluster renewal = new FakeRedisCluster();
        try (var provider = provider(renewal, "renewal")) {
            provider.getMachineId();
            renewal.fail = true;
            assertThat(provider.renewOnce()).isEqualTo(-1);
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(CommonException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "prod:extra", "prod{1}", "prod}1", "prod env"})
    void rejectsInvalidEnvAndAppSegments(String invalid) {
        FakeRedisCluster redis = new FakeRedisCluster();
        assertThatThrownBy(() -> new RedisMachineIdProvider(redis.client(), invalid, "order-service", LEASE, RENEW))
                .isInstanceOf(CommonException.class).hasMessageContaining("egon.cola.component.id.redis.env");
        assertThatThrownBy(() -> new RedisMachineIdProvider(redis.client(), "prod", invalid, LEASE, RENEW))
                .isInstanceOf(CommonException.class).hasMessageContaining("egon.cola.component.id.redis.app");
    }

    @Test
    void rejectsInvalidLeaseAndRenewDurations() {
        FakeRedisCluster redis = new FakeRedisCluster();
        assertThatThrownBy(() -> new RedisMachineIdProvider(redis.client(), "prod", "order-service", Duration.ZERO, RENEW))
                .isInstanceOf(CommonException.class).hasMessageContaining("egon.cola.component.id.redis.lease-duration must be positive");
        assertThatThrownBy(() -> new RedisMachineIdProvider(redis.client(), "prod", "order-service", LEASE, LEASE))
                .isInstanceOf(CommonException.class)
                .hasMessageContaining("egon.cola.component.id.redis.renew-interval must be smaller than lease-duration");
    }

    private static RedisMachineIdProvider provider(FakeRedisCluster redis, String owner) {
        return provider(redis, "prod", "order-service", owner);
    }

    private static RedisMachineIdProvider provider(FakeRedisCluster redis, String env, String app, String owner) {
        return new RedisMachineIdProvider(redis.client(), env, app, LEASE, RENEW, owner,
                () -> redis.nanoNanos, () -> redis.wallMillis);
    }

    /** Models replies and local timing only; the independent Cluster IT executes the actual Lua. */
    static final class FakeRedisCluster {
        private final Map<String, String> values = new HashMap<>();
        private String runId = "a".repeat(40) + ":1";
        private long nowMillis = System.currentTimeMillis();
        private long wallMillis = nowMillis;
        private long nanoNanos;
        private boolean fail;
        private Runnable afterAcquire = () -> { }, afterRenew = () -> { }, beforeRelease = () -> { };
        private List<String> lastAcquisitionKeys = List.of(), lastRenewKeys = List.of(), lastReleaseKeys = List.of();

        private FakeRedisCluster() {
            provision("prod", "order-service");
            provision("test", "order-service");
            provision("prod", "user-service");
        }

        RedissonClient client() {
            RedissonClient client = mock(RedissonClient.class);
            RScript script = mock(RScript.class);
            when(client.getScript(any(Codec.class))).thenReturn(script);
            when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                return evaluate(invocation.getArgument(1), invocation.getArgument(3), Arrays.copyOfRange(args, 4, args.length));
            });
            return client;
        }

        private synchronized Object evaluate(String script, List<Object> keys, Object[] args) {
            if (fail) { throw new RedisException("synthetic unavailable"); }
            String cursorKey = keys.getFirst().toString();
            String cursor = values.get(cursorKey);
            boolean release = script.contains("common-id:machine-lease:release");
            if (cursor == null) { return release ? -2L : List.of(-2L); }
            String[] parts = cursor.split("\\|");
            if (parts.length != 3 || !parts[0].equals("V1")) { return release ? -4L : List.of(-4L); }
            if (!parts[1].equals(runId)) { return release ? -3L : List.of(-3L); }
            if (script.contains("common-id:machine-lease:acquire")) {
                lastAcquisitionKeys = keys.stream().map(Object::toString).toList();
                if (keys.size() != 1025) { return List.of(-2L); }
                for (int i = 1; i < keys.size(); i++) {
                    if (!values.containsKey(keys.get(i).toString())) { return List.of(-2L); }
                    if (slot(keys.get(i).toString()) == null) { return List.of(-4L); }
                }
                int start = Integer.parseInt(parts[2]);
                long lease = Long.parseLong(args[1].toString());
                for (int i = 0; i < 1024; i++) {
                    int candidate = (start + i) % 1024;
                    String key = keys.get(candidate + 1).toString();
                    SlotRecordVO slot = slot(key);
                    if (!slot.state().equals("A") && nowMillis > slot.upper()) {
                        long lower = slot.state().equals("F") ? 0 : slot.upper() + 1;
                        long upper = nowMillis + lease;
                        values.put(key, "A|" + args[0] + "|" + upper + "|" + lower + "|" + upper);
                        values.put(cursorKey, "V1|" + runId + "|" + ((candidate + 1) % 1024));
                        var reply = List.of(1L, (long) candidate, nowMillis, upper, lower, upper, runId);
                        afterAcquire.run();
                        return reply;
                    }
                }
                return List.of(0L);
            }
            String key = keys.get(1).toString();
            SlotRecordVO slot = slot(key);
            if (script.contains("common-id:machine-lease:renew")) {
                lastRenewKeys = keys.stream().map(Object::toString).toList();
                if (!runId.equals(args[2])) { return List.of(-3L); }
                if (slot == null) { return List.of(-2L); }
                if (!slot.state().equals("A") || !slot.owner().equals(args[0]) || nowMillis >= slot.expiry()) {
                    return List.of(0L);
                }
                long upper = nowMillis + Long.parseLong(args[1].toString());
                if (upper < slot.upper() || nowMillis < slot.floor()) { return List.of(-5L); }
                values.put(key, "A|" + args[0] + "|" + upper + "|" + slot.floor() + "|" + upper);
                var reply = List.of(1L, Long.parseLong(args[3].toString()), nowMillis, upper, slot.floor(), upper, runId);
                afterRenew.run();
                return reply;
            }
            if (release) {
                lastReleaseKeys = keys.stream().map(Object::toString).toList();
                beforeRelease.run();
                if (!runId.equals(args[1])) { return -3L; }
                if (slot == null) { return -2L; }
                if (slot.state().equals("A") && slot.owner().equals(args[0])) {
                    values.put(key, "R|" + slot.upper());
                    return 1L;
                }
                return 0L;
            }
            throw new AssertionError("Unexpected Lua action");
        }

        private void provision(String env, String app) {
            String prefix = "egon:cola:id:{" + env + ":" + app + "}:";
            values.put(prefix + "cursor", "V1|" + runId + "|0");
            for (int i = 0; i < 1024; i++) { values.put(prefix + "slot:" + i, "F|0"); }
        }
        private void fillActive(String env, String app) {
            String prefix = "egon:cola:id:{" + env + ":" + app + "}:slot:";
            for (int i = 0; i < 1024; i++) { forceOwner(prefix + i, "holder-" + i); }
        }
        private void forceOwner(String key, String owner) {
            long upper = nowMillis + LEASE.toMillis();
            values.put(key, "A|" + owner + "|" + upper + "|0|" + upper);
        }
        private SlotRecordVO slot(String key) {
            String value = values.get(key);
            if ("F|0".equals(value)) { return new SlotRecordVO("F", "", 0, 0, 0); }
            if (value == null) { return null; }
            try {
                String[] p = value.split("\\|");
                if (p.length == 2 && p[0].equals("R")) {
                    return new SlotRecordVO("R", "", 0, 0, Long.parseLong(p[1]));
                }
                if (p.length == 5 && p[0].equals("A")) {
                    return new SlotRecordVO("A", p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), Long.parseLong(p[4]));
                }
                return null;
            } catch (NumberFormatException bad) { return null; }
        }
        private void advance(Duration duration) {
            nowMillis += duration.toMillis();
            advanceLocal(duration);
        }
        private void advanceLocal(Duration duration) {
            wallMillis += duration.toMillis();
            nanoNanos += duration.toNanos();
        }
        private int cursor(String key) { return Integer.parseInt(values.get(key).split("\\|")[2]); }
        private String cursorKey() { return "egon:cola:id:{prod:order-service}:cursor"; }
        private String slotKey(int slot) { return "egon:cola:id:{prod:order-service}:slot:" + slot; }
        private record SlotRecordVO(String state, String owner, long expiry, long floor, long upper) { }
    }
}
