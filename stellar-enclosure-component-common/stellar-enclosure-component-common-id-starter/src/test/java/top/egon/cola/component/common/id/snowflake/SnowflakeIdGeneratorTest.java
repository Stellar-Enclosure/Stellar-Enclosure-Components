package top.egon.cola.component.common.id.snowflake;

import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.id.provider.MachineIdProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.egon.cola.component.common.id.exception.ClockMovedBackwardException;
import top.egon.cola.component.common.id.exception.IdGenerationInterruptedException;
import top.egon.cola.component.common.id.exception.SnowflakeTimestampOutOfRangeException;
import top.egon.cola.component.common.id.time.TimeSource;
import java.time.Duration;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SnowflakeIdGeneratorTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    private static final long TEST_TIME = SnowflakeIdLayout.EPOCH_MILLIS + 1_000;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void bothStaticEntrancesUseTheBoundProviderAndDecodeTheActualEncodedTimestamp() throws Exception {
        try (var isolated = new IsolatedSnowflake()) {
            var provider = new FencedProvider();
            isolated.initialize(17, provider);
            long before = System.currentTimeMillis();
            long first = isolated.nextLongId();
            assertEquals(SnowflakeIdLayout.EPOCH_MILLIS + SnowflakeIdLayout.elapsedMillis(first), provider.decodedTimestamp.get());
            assertTrue(provider.decodedTimestamp.get() >= before);
            assertTrue(provider.decodedTimestamp.get() <= System.currentTimeMillis());
            long second = Long.parseLong(isolated.nextId());
            assertTrue(second > first);
            assertEquals(17, SnowflakeIdLayout.machineId(second));
            assertEquals(2, provider.issued.get());
            isolated.initialize(17, provider);
            assertEquals(2, provider.issued.get(), "rechecking an identical binding must not generate an ID");
        }
    }

    @Test
    void revocationAfterGenerationDiscardsTheIdAndFencesBothEntrances() throws Exception {
        try (var isolated = new IsolatedSnowflake()) {
            var provider = new FencedProvider();
            isolated.initialize(17, provider);
            provider.revokeAfterGeneration = true;
            assertLeaseFailure(() -> isolated.nextLongId());
            assertLeaseFailure(() -> isolated.nextId());
            assertEquals(1, provider.issued.get(), "a fenced provider must not call the engine again");
            assertLeaseFailure(() -> isolated.initialize(17, provider));
        }
    }

    @Test
    void anInitializationFailureDoesNotPublishAnUnusableBinding() throws Exception {
        try (var isolated = new IsolatedSnowflake()) {
            var rejected = new FencedProvider();
            rejected.available.set(false);
            assertLeaseFailure(() -> isolated.initialize(17, rejected));
            assertInstanceOf(CommonException.class,
                    assertThrows(InvocationTargetException.class, isolated::nextLongId).getCause());
            var accepted = new FencedProvider();
            isolated.initialize(17, accepted);
            assertTrue(isolated.nextLongId() > 0);
        }
    }

    @Test
    void aLiveEngineCannotRebindAcrossProviderIdentityOrStaticAndDynamicModes() throws Exception {
        try (var isolated = new IsolatedSnowflake()) {
            var firstProvider = new FencedProvider();
            isolated.initialize(17, firstProvider);
            long first = isolated.nextLongId();
            isolated.initialize(17, firstProvider);
            assertTrue(isolated.nextLongId() > first);
            var replacement = new FencedProvider();
            assertEquals(firstProvider, replacement, "fixture equality must not imply the same lease owner");
            assertInstanceOf(CommonException.class, assertThrows(InvocationTargetException.class,
                    () -> isolated.initialize(17, replacement)).getCause());
            assertInstanceOf(CommonException.class, assertThrows(InvocationTargetException.class,
                    () -> isolated.initializeStatic(17)).getCause());
        }
        try (var isolated = new IsolatedSnowflake()) {
            isolated.initializeStatic(17);
            assertInstanceOf(CommonException.class, assertThrows(InvocationTargetException.class,
                    () -> isolated.initialize(17, new FencedProvider())).getCause());
        }
    }

    @Test
    void functionalProvidersRemainCompatibleAndDefaultIssuanceRechecksAfterGeneratorWork() {
        MachineIdProvider fixed = () -> 17;
        assertEquals(17, fixed.getMachineId());
        assertEquals(41, fixed.issue(() -> 41, id -> id));
        fixed.assertIssuanceAvailable();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger generated = new AtomicInteger();
        MachineIdProvider guarded = new MachineIdProvider() {
            @Override public long getMachineId() { return 17; }
            @Override public void assertIssuanceAvailable() {
                if (!available.get()) {
                    throw new IllegalStateException("lease unavailable");
                }
            }
        };
        assertThrows(IllegalStateException.class, () -> guarded.issue(() -> {
            generated.incrementAndGet();
            available.set(false);
            return 42;
        }, id -> id));
        assertThrows(IllegalStateException.class, () -> guarded.issue(() -> {
            generated.incrementAndGet();
            return 43;
        }, id -> id));
        assertEquals(1, generated.get());
    }

    private static void assertLeaseFailure(org.junit.jupiter.api.function.Executable action) {
        assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class, action).getCause());
    }

    private static final class FencedProvider implements MachineIdProvider {
        private final AtomicBoolean available = new AtomicBoolean(true);
        private final AtomicInteger issued = new AtomicInteger();
        private final AtomicLong decodedTimestamp = new AtomicLong();
        private boolean revokeAfterGeneration;

        @Override public long getMachineId() { return 17; }

        @Override
        public long issue(LongSupplier generator, LongUnaryOperator timestampDecoder) {
            assertIssuanceAvailable();
            long id = generator.getAsLong();
            issued.incrementAndGet();
            decodedTimestamp.set(timestampDecoder.applyAsLong(id));
            if (revokeAfterGeneration) {
                available.set(false);
            }
            assertIssuanceAvailable();
            return id;
        }

        @Override
        public void assertIssuanceAvailable() {
            if (!available.get()) {
                throw new IllegalStateException("lease unavailable");
            }
        }

        @Override public boolean equals(Object other) { return other instanceof FencedProvider; }
        @Override public int hashCode() { return 17; }
    }

    /** Isolates the one-time process binding without adding a production reset or mutating static state. */
    private static final class IsolatedSnowflake implements AutoCloseable {
        private final URLClassLoader loader;
        private final Class<?> type;

        private IsolatedSnowflake() throws ClassNotFoundException {
            loader = new URLClassLoader(new URL[]{SnowflakeIdGenerator.class.getProtectionDomain().getCodeSource().getLocation()},
                    SnowflakeIdGenerator.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith("top.egon.cola.component.common.id.snowflake.")) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded == null) {
                                loaded = findClass(name);
                            }
                            if (resolve) {
                                resolveClass(loaded);
                            }
                            return loaded;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
            type = loader.loadClass(SnowflakeIdGenerator.class.getName());
        }

        private void initialize(long machineId, MachineIdProvider provider) throws ReflectiveOperationException {
            type.getMethod("initialize", Long.class, Duration.class, MachineIdProvider.class)
                    .invoke(null, machineId, Duration.ofMillis(5), provider);
        }

        private void initializeStatic(long machineId) throws ReflectiveOperationException {
            type.getMethod("initialize", Long.class, Duration.class).invoke(null, machineId, Duration.ofMillis(5));
        }

        private long nextLongId() throws ReflectiveOperationException {
            return (Long) type.getMethod("nextLongId").invoke(null);
        }

        private String nextId() throws ReflectiveOperationException {
            return (String) type.getMethod("nextId").invoke(null);
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }
    }

    @Test
    void layoutComposesIdAndRoundTripsGeneratorState() {
        long id = SnowflakeIdLayout.compose(123L, 17, 4095);
        long state = SnowflakeIdLayout.packState(123L, 4095);

        assertEquals((123L << 22) | (17L << 12) | 4095L, id);
        assertEquals(123L, SnowflakeIdLayout.elapsedMillis(id));
        assertEquals(17, SnowflakeIdLayout.machineId(id));
        assertEquals(4095, SnowflakeIdLayout.sequence(id));
        assertEquals(123L, SnowflakeIdLayout.stateElapsedMillis(state));
        assertEquals(4095, SnowflakeIdLayout.stateSequence(state));
    }

    @Test
    void generatesStrictlyIncreasingIdsInOneInstance() {
        SnowflakeLongIdGenerator generator = generator(7, SnowflakeIdLayout.EPOCH_MILLIS + 100);

        long first = generator.nextLongId();
        long second = generator.nextLongId();

        assertTrue(second > first);
        assertEquals(0, SnowflakeIdLayout.sequence(first));
        assertEquals(1, SnowflakeIdLayout.sequence(second));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1023L})
    void acceptsMachineIdBoundaries(long machineId) {
        SnowflakeLongIdGenerator generator = generator(machineId, SnowflakeIdLayout.EPOCH_MILLIS + 1);

        assertEquals(machineId, SnowflakeIdLayout.machineId(generator.nextLongId()));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 1024L})
    void rejectsMachineIdOutsideTenBits(long machineId) {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeLongIdGenerator(machineId));
    }

    @Test
    void rejectsNegativeClockBackwardDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnowflakeLongIdGenerator(1, Duration.ofMillis(-1)));
    }

    @Test
    void differentMachineIdsDoNotCollideAcrossAFullSequence() {
        Set<Long> firstMachineIds = generateAtFixedTime(1, TEST_TIME, 4_096);
        Set<Long> secondMachineIds = generateAtFixedTime(2, TEST_TIME, 4_096);

        assertEquals(4_096, firstMachineIds.size());
        assertEquals(4_096, secondMachineIds.size());
        assertTrue(Collections.disjoint(firstMachineIds, secondMachineIds));
    }

    @Test
    void independentGeneratorsWithSameMachineIdCanGenerateDuplicates() {
        SnowflakeLongIdGenerator firstGenerator = generator(17, TEST_TIME);
        SnowflakeLongIdGenerator secondGenerator = generator(17, TEST_TIME);

        long first = firstGenerator.nextLongId();
        long second = secondGenerator.nextLongId();

        assertEquals(first, second);
        assertEquals(17, SnowflakeIdLayout.machineId(first));
        assertEquals(0, SnowflakeIdLayout.sequence(first));
    }

    @Test
    void exactEpochStillGeneratesPositiveId() {
        long id = generator(0, SnowflakeIdLayout.EPOCH_MILLIS).nextLongId();

        assertTrue(id > 0);
        assertEquals(1, SnowflakeIdLayout.sequence(id));
    }

    @Test
    void acceptsLastRepresentableMillisecond() {
        long lastTime = SnowflakeIdLayout.EPOCH_MILLIS + SnowflakeIdLayout.MAX_ELAPSED_MILLIS;

        long id = generator(1023, lastTime).nextLongId();

        assertEquals(SnowflakeIdLayout.MAX_ELAPSED_MILLIS, SnowflakeIdLayout.elapsedMillis(id));
        assertEquals(1023, SnowflakeIdLayout.machineId(id));
    }

    @Test
    void rejectsTimeBeforeEpoch() {
        SnowflakeLongIdGenerator generator = generator(1, SnowflakeIdLayout.EPOCH_MILLIS - 1);

        assertThrows(SnowflakeTimestampOutOfRangeException.class, generator::nextLongId);
    }

    @Test
    void rejectsTimeAfterTimestampBitsAreExhausted() {
        long exhausted = SnowflakeIdLayout.EPOCH_MILLIS + SnowflakeIdLayout.MAX_ELAPSED_MILLIS + 1;
        SnowflakeLongIdGenerator generator = generator(1, exhausted);

        assertThrows(SnowflakeTimestampOutOfRangeException.class, generator::nextLongId);
    }

    @Test
    void generatesAllSequencesThenWaitsForNextMillisecond() {
        assertTimeoutPreemptively(WAIT_TIMEOUT, () -> {
            ControllableTimeSource timeSource = new ControllableTimeSource(TEST_TIME);
            SnowflakeLongIdGenerator generator = generator(5, Duration.ofMillis(5), timeSource);
            List<Long> ids = new ArrayList<>(4_096);

            for (int sequence = 0; sequence < 4_096; sequence++) {
                long id = generator.nextLongId();
                assertTrue(id > 0L);
                assertEquals(TEST_TIME - SnowflakeIdLayout.EPOCH_MILLIS, SnowflakeIdLayout.elapsedMillis(id));
                assertEquals(5, SnowflakeIdLayout.machineId(id));
                assertEquals(sequence, SnowflakeIdLayout.sequence(id));
                if (sequence > 0) {
                    assertTrue(id > ids.get(sequence - 1));
                }
                ids.add(id);
            }
            assertEquals(4_096, new HashSet<>(ids).size());

            long readsBeforeWait = timeSource.readCount();
            PendingGeneration pending = startVirtualGeneration(generator, "snowflake-sequence-wait");
            try {
                awaitReadCountAtLeast(timeSource, readsBeforeWait + 2L, WAIT_TIMEOUT);
                assertTrue(pending.thread().isAlive(), "4097th generation must wait for time to advance");
                assertNull(pending.result().get());

                timeSource.advanceMillis(1L);
                awaitTermination(pending.thread(), WAIT_TIMEOUT);
            } finally {
                stopIfAlive(pending.thread());
            }

            assertNull(pending.failure().get());
            Long nextResult = pending.result().get();
            assertNotNull(nextResult);
            long nextId = nextResult;
            assertTrue(nextId > ids.get(ids.size() - 1));
            assertEquals(TEST_TIME + 1L - SnowflakeIdLayout.EPOCH_MILLIS, SnowflakeIdLayout.elapsedMillis(nextId));
            assertEquals(0, SnowflakeIdLayout.sequence(nextId));
        });
    }

    @Test
    void smallClockRollbackWaitsForManualRecovery() {
        assertTimeoutPreemptively(WAIT_TIMEOUT, () -> {
            ControllableTimeSource timeSource = new ControllableTimeSource(TEST_TIME);
            SnowflakeLongIdGenerator generator = generator(3, Duration.ofSeconds(1), timeSource);
            long first = generator.nextLongId();

            timeSource.setCurrentTimeMillis(TEST_TIME - 3L);
            long readsBeforeWait = timeSource.readCount();
            PendingGeneration pending = startVirtualGeneration(generator, "snowflake-clock-recovery");
            try {
                awaitReadCountAtLeast(timeSource, readsBeforeWait + 2L, WAIT_TIMEOUT);
                assertTrue(pending.thread().isAlive(), "generation must wait while the clock is behind");

                timeSource.setCurrentTimeMillis(TEST_TIME);
                awaitTermination(pending.thread(), WAIT_TIMEOUT);
            } finally {
                stopIfAlive(pending.thread());
            }

            assertNull(pending.failure().get());
            Long secondResult = pending.result().get();
            assertNotNull(secondResult);
            long second = secondResult;
            assertTrue(second > first);
            assertEquals(1, SnowflakeIdLayout.sequence(second));
        });
    }

    @Test
    void largeClockRollbackFailsImmediatelyWithDiagnostics() {
        ControllableTimeSource timeSource = new ControllableTimeSource(TEST_TIME);
        SnowflakeLongIdGenerator generator = generator(9, Duration.ofMillis(5), timeSource);
        generator.nextLongId();
        timeSource.setCurrentTimeMillis(TEST_TIME - 6L);

        ClockMovedBackwardException exception = assertThrows(
                ClockMovedBackwardException.class, generator::nextLongId);

        assertEquals(TEST_TIME - 6L, exception.currentTimeMillis());
        assertEquals(TEST_TIME, exception.lastTimeMillis());
        assertEquals(6L, exception.backwardMillis());
        assertEquals(9, exception.machineId());
        assertTrue(exception.getMessage().contains("machineId=9"));
    }

    @Test
    void stalledSmallRollbackFailsWithinBoundedWait() {
        ControllableTimeSource timeSource = new ControllableTimeSource(TEST_TIME);
        SnowflakeLongIdGenerator generator = generator(2, Duration.ofMillis(1), timeSource);
        generator.nextLongId();
        timeSource.setCurrentTimeMillis(TEST_TIME - 1L);

        assertTimeoutPreemptively(WAIT_TIMEOUT,
                () -> assertThrows(ClockMovedBackwardException.class, generator::nextLongId));
    }

    @Test
    void interruptedVirtualThreadWaitingForNextMillisecondThrowsDedicatedException() {
        assertTimeoutPreemptively(WAIT_TIMEOUT, () -> {
            ControllableTimeSource timeSource = new ControllableTimeSource(TEST_TIME);
            SnowflakeLongIdGenerator generator = generator(4, Duration.ofMillis(5), timeSource);
            for (int i = 0; i < 4_096; i++) {
                generator.nextLongId();
            }

            long readsBeforeWait = timeSource.readCount();
            PendingGeneration pending = startVirtualGeneration(generator, "snowflake-interrupt-wait");
            try {
                awaitReadCountAtLeast(timeSource, readsBeforeWait + 2L, WAIT_TIMEOUT);
                assertTrue(pending.thread().isAlive(), "generation must still be waiting");
                pending.thread().interrupt();
                awaitTermination(pending.thread(), WAIT_TIMEOUT);
            } finally {
                stopIfAlive(pending.thread());
            }

            assertNull(pending.result().get());
            assertInstanceOf(IdGenerationInterruptedException.class, pending.failure().get());
            assertTrue(pending.interruptedAtExit().get());
        });
    }

    @Test
    void largeDeterministicBatchIsUniqueAndStrictlyIncreasing() {
        AtomicLong reads = new AtomicLong();
        TimeSource timeSource = () -> TEST_TIME + reads.getAndIncrement() / 2_048;
        SnowflakeLongIdGenerator generator = generator(11, Duration.ofMillis(5), timeSource);
        List<Long> ids = new ArrayList<>(100_000);

        for (int i = 0; i < 100_000; i++) {
            ids.add(generator.nextLongId());
        }

        assertEquals(100_000, new HashSet<>(ids).size());
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1));
        }
    }

    private Set<Long> generateAtFixedTime(long machineId, long currentTimeMillis, int count) {
        SnowflakeLongIdGenerator generator = generator(machineId, currentTimeMillis);
        Set<Long> ids = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextLongId());
        }
        return ids;
    }

    private SnowflakeLongIdGenerator generator(long machineId, long currentTimeMillis) {
        return generator(machineId, Duration.ofMillis(5), () -> currentTimeMillis);
    }

    private SnowflakeLongIdGenerator generator(long machineId, Duration maxClockBackward,
                                             TimeSource timeSource) {
        return new SnowflakeLongIdGenerator(machineId, maxClockBackward, timeSource);
    }

    private PendingGeneration startVirtualGeneration(SnowflakeLongIdGenerator generator, String threadName) {
        AtomicReference<Long> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAtExit = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().name(threadName).unstarted(() -> {
            try {
                result.set(generator.nextLongId());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                interruptedAtExit.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        return new PendingGeneration(thread, result, failure, interruptedAtExit);
    }

    private void awaitReadCountAtLeast(ControllableTimeSource timeSource, long expectedReadCount,
                                       Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (timeSource.readCount() < expectedReadCount && System.nanoTime() < deadline) {
            LockSupport.parkNanos(100_000L);
        }
        assertTrue(timeSource.readCount() >= expectedReadCount,
                "generator did not enter the clock wait loop before the timeout");
    }

    private void awaitTermination(Thread thread, Duration timeout) throws InterruptedException {
        thread.join(timeout.toMillis());
        assertFalse(thread.isAlive(), "virtual thread did not terminate before the timeout");
    }

    private void stopIfAlive(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(WAIT_TIMEOUT.toMillis());
        }
    }

    private record PendingGeneration(Thread thread, AtomicReference<Long> result,
                                     AtomicReference<Throwable> failure,
                                     AtomicBoolean interruptedAtExit) {
    }
}
