package top.egon.cola.component.outbox.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.deadletter.OutboxDeadLetterNotifier;
import top.egon.cola.component.outbox.delivery.DefaultDeliveryFailureClassifier;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.event.OutboxDeadLetterEvent;
import top.egon.cola.component.outbox.observability.OutboxMetrics;
import top.egon.cola.component.outbox.common.exception.OutboxStateMachineException;
import top.egon.cola.component.outbox.store.NewOutboxRecord;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStatus;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.store.OutboxClaimCommand;
import top.egon.cola.component.outbox.store.OutboxClaimBatchBO;
import top.egon.cola.component.outbox.store.OutboxFinishCommand;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.observability.NoopOutboxMetrics;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleSignalEnum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {

    private final RecordingOutboxStore store = new RecordingOutboxStore();
    private final RecordingDeliveryHandler handler = new RecordingDeliveryHandler();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final OutboxLifecycleService lifecycleService = mock(OutboxLifecycleService.class);
    private final List<OutboxDeadLetterEvent> deadEvents = new ArrayList<>();
    private final AtomicInteger taskSubmissions = new AtomicInteger();
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        taskSubmissions.set(0);
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getPolling().setBatchSize(10);
        properties.getPolling().setConcurrency(2);
        properties.getDelivery().setQueueCapacity(8);
        when(lifecycleService.evaluate(anyString(), eq(OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.SUCCEEDED);
        when(lifecycleService.evaluate(anyString(), eq(OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE), anyInt(), anyInt()))
                .thenAnswer(invocation -> invocation.<Integer>getArgument(2)
                        >= invocation.<Integer>getArgument(3) ? OutboxStatus.DEAD : OutboxStatus.RETRY_WAIT);
        when(lifecycleService.evaluate(anyString(), eq(OutboxLifecycleSignalEnum.DELIVERY_PERMANENT), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.DEAD);
        dispatcher = dispatcher(
                new DeliveryHandlerRegistry(List.of(handler)),
                properties
        );
    }

    @Test
    void migrationModeDoesNotScheduleOrTouchTheStoreFromEitherEntryPoint() {
        store.add(record(1, "message-1", "http", 1, 10));
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        OutboxMpStorageProperties storageProperties = new OutboxMpStorageProperties().setMigrationMode(true);
        DeliveryHandlerRegistry registry = new DeliveryHandlerRegistry(List.of(handler));
        OutboxDispatcher maintenanceDispatcher = dispatcher(registry, properties, storageProperties);

        maintenanceDispatcher.submitDue();
        maintenanceDispatcher.submitMessageIds(List.of("message-1"));

        assertThat(store.claimDueCount).isZero();
        assertThat(store.claimByMessageIdsCount).isZero();
        assertThat(store.backlogCount).isZero();
        assertThat(store.succeeded).isEmpty();
        assertThat(store.retried).isEmpty();
        assertThat(store.dead).isEmpty();
        assertThat(handler.results).isEmpty();
        assertThat(handler.deliveryCount).isZero();
        assertThat(taskSubmissions).hasValue(0);
        assertThat(metrics.deliveryCount).isZero();
        assertThat(metrics.retryCount).isZero();
        assertThat(metrics.deadCount).isZero();
        assertThat(metrics.leaseLostCount).isZero();
    }

    @Test
    void enabledExhaustedRecoveryRunsBeforeTheNormalPoll() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getRetry().setRecoverExhausted(true);
        properties.getRetry().setExhaustedRetryDelay(Duration.ofMinutes(7));
        var subject = dispatcher(new DeliveryHandlerRegistry(List.of(handler)), properties);
        subject.dispatchDue();
        assertThat(store.recoverCount).isEqualTo(1);
        assertThat(store.recoverDelay).isEqualTo(Duration.ofMinutes(7));
        assertThat(store.claimDueCount).isEqualTo(1);
    }

    @Test
    void recoveryFailureDoesNotBlockOrdinaryDelivery() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getRetry().setRecoverExhausted(true);
        store.failRecovery = true;
        store.add(record(1, "message-1", "http", 1, 10));
        handler.results.put("message-1", DeliveryResult.success());
        dispatcher(new DeliveryHandlerRegistry(List.of(handler)), properties).dispatchDue();
        assertThat(store.succeeded).containsExactly("message-1");
    }

    @Test
    void permanentFailureCannotUseTheRecoverableExhaustionMarker() {
        store.add(record(1, "permanent", "http", 10, 10));
        handler.results.put("permanent", DeliveryResult.permanentFailure("OUTBOX_RETRY_EXHAUSTED", "invalid"));
        dispatcher.dispatchDue();
        assertThat(store.deadCodes.get("permanent")).isEqualTo("OUTBOX_PERMANENT_FAILURE");
    }

    @Test
    void shouldMarkSuccessfulDeliveryWithCurrentOwner() {
        store.add(record(1, "message-1", "http", 11, 10));
        handler.results.put("message-1", DeliveryResult.success());

        dispatcher.dispatchDue();

        assertThat(store.succeeded).containsExactly("message-1");
        assertThat(store.retried).isEmpty();
        assertThat(store.dead).isEmpty();
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 11, 10);
    }

    @Test
    void shouldRetryRetryableFailureWhenAttemptsRemain() {
        store.add(record(1, "message-1", "http", 2, 10));
        handler.results.put(
                "message-1",
                DeliveryResult.retryableFailure("HTTP_503", "unavailable")
        );

        dispatcher.dispatchDue();

        assertThat(store.retried.get("message-1")).isEqualTo(Duration.ofSeconds(2));
        assertThat(metrics.retryCount).isEqualTo(1);
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE, 2, 10);
    }

    @Test
    void shouldPassRegisteredHandlerChannelsWhenClaimingMessages() {
        store.filterByChannels = true;
        store.add(
                record(1, "message-1", "http", 1, 10),
                record(2, "message-2", "state-machine", 1, 10)
        );
        handler.results.put("message-1", DeliveryResult.success());

        dispatcher.dispatchDue();
        assertThat(store.lastClaimDueChannels).containsExactly("http");
        assertThat(store.succeeded).containsExactly("message-1");

        dispatcher.dispatchMessageIds(List.of("message-1", "message-2"));
        assertThat(store.lastClaimByIdsChannels).containsExactly("http");
    }

    @Test
    void shouldDeadLetterExhaustedFailureWithoutStoppingBatch() {
        store.add(
                record(1, "poison", "http", 10, 10),
                record(2, "healthy", "http", 1, 10)
        );
        handler.results.put(
                "poison",
                DeliveryResult.retryableFailure("HTTP_503", "unavailable")
        );
        handler.results.put("healthy", DeliveryResult.success());

        dispatcher.dispatchDue();

        assertThat(store.dead).containsExactly("poison");
        assertThat(store.deadCodes.get("poison")).isEqualTo("OUTBOX_RETRY_EXHAUSTED");
        assertThat(store.succeeded).containsExactly("healthy");
        assertThat(metrics.deadCount).isEqualTo(1);
        assertThat(deadEvents).extracting(OutboxDeadLetterEvent::messageId)
                .containsExactly("poison");
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE, 10, 10);
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 1, 10);
    }

    @Test
    void shouldObserveLeaseLossWithoutOverwritingNewOwner() {
        store.add(record(1, "message-1", "http", 1, 10));
        store.allowOwnerUpdate = false;
        handler.results.put("message-1", DeliveryResult.success());

        dispatcher.dispatchDue();

        assertThat(metrics.leaseLostCount).isEqualTo(1);
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 1, 10);
    }

    @Test
    void shouldNotCountRetryWhenTheStoreRejectsTheOldOwner() {
        store.add(record(1, "message-1", "http", 2, 10));
        store.allowOwnerUpdate = false;
        handler.results.put("message-1", DeliveryResult.retryableFailure("HTTP_503", "unavailable"));

        dispatcher.dispatchDue();

        assertThat(store.retried).isEmpty();
        assertThat(metrics.retryCount).isZero();
        assertThat(metrics.leaseLostCount).isEqualTo(1);
    }

    @Test
    void shouldNotPublishDeadLetterWhenTheStoreRejectsTheOldOwner() {
        store.add(record(1, "message-1", "http", 1, 10));
        store.allowOwnerUpdate = false;
        handler.results.put("message-1", DeliveryResult.permanentFailure("INVALID", "invalid"));

        dispatcher.dispatchDue();

        assertThat(store.dead).isEmpty();
        assertThat(metrics.deadCount).isZero();
        assertThat(metrics.leaseLostCount).isEqualTo(1);
        assertThat(deadEvents).isEmpty();
    }

    @Test
    void shouldLeaveRecordProcessingWhenErrorEscapes() {
        store.add(record(1, "message-1", "http", 1, 10));
        handler.errors.put("message-1", new AssertionError("fatal"));

        assertThatThrownBy(dispatcher::dispatchDue).isInstanceOf(AssertionError.class);

        assertThat(store.succeeded).isEmpty();
        assertThat(store.retried).isEmpty();
        assertThat(store.dead).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(lifecycleService);
    }

    @Test
    void shouldDeadLetterMissingHandlerWithoutStoppingBatch() {
        store.add(
                record(1, "missing", "removed", 1, 10),
                record(2, "healthy", "http", 1, 10)
        );
        handler.results.put("healthy", DeliveryResult.success());

        dispatcher.dispatchDue();

        assertThat(store.deadCodes.get("missing")).isEqualTo("OUTBOX_HANDLER_MISSING");
        assertThat(store.succeeded).containsExactly("healthy");
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_PERMANENT, 1, 10);
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 1, 10);
    }

    @Test
    void shouldRejectUnexpectedLifecycleTargetBeforeWriting() {
        store.add(record(1, "message-1", "http", 1, 10));
        handler.results.put("message-1", DeliveryResult.success());
        when(lifecycleService.evaluate(anyString(), eq(OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.PROCESSING);

        assertThatThrownBy(dispatcher::dispatchDue)
                .isInstanceOf(OutboxStateMachineException.class);

        assertThat(store.succeeded).isEmpty();
        assertThat(store.retried).isEmpty();
        assertThat(store.dead).isEmpty();
        verify(lifecycleService).evaluate("PROCESSING", OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 1, 10);
    }

    @Test
    void four_busy_workers_do_not_claim_a_fifth_row_and_returned_readiness_keeps_the_wakeup() throws Exception {
        CountDownLatch fourRunning = new CountDownLatch(4);
        CountDownLatch fifthRunning = new CountDownLatch(1);
        Semaphore finishOne = new Semaphore(0);
        try (SafeHarness safe = new SafeHarness(4, List.of(0), context -> {
            if (context.messageId().equals("safe-5")) fifthRunning.countDown();
            else {
                fourRunning.countDown();
                if (!finishOne.tryAcquire(5, TimeUnit.SECONDS)) throw new AssertionError("worker was not released");
            }
            return DeliveryResult.success();
        })) {
            for (int index = 1; index <= 5; index++) safe.add(index, 0);
            safe.start();
            safe.subject.submitDue();
            assertThat(fourRunning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(safe.claimed).hasValue(4);
            safe.subject.submitMessageIds(List.of("safe-5"));
            safe.subject.submitDue();
            assertThat(fifthRunning.await(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(safe.claimed).hasValue(4);
            assertThat(safe.commands).allSatisfy(command -> assertThat(command.getLimit()).isEqualTo(1));

            finishOne.release();
            assertThat(fifthRunning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(safe.claimed).hasValue(5);
            finishOne.release(3);
            safe.stop();
            assertThat(safe.delivered).containsExactlyInAnyOrder("safe-1", "safe-2", "safe-3", "safe-4", "safe-5");
            verify(safe.mockStore, never()).claimDue(anyInt(), anyString(), any(Duration.class));
            verify(safe.mockStore, never()).claimByMessageIds(any(), anyInt(), anyString(), any(Duration.class));
        } finally {
            finishOne.release(4);
        }
    }

    @Test
    void a_committed_shard_hands_off_while_another_shard_claim_is_still_blocked() throws Exception {
        CountDownLatch blockedShardEntered = new CountDownLatch(1);
        CountDownLatch unblockShard = new CountDownLatch(1);
        CountDownLatch firstShardDelivered = new CountDownLatch(1);
        try (SafeHarness safe = new SafeHarness(2, List.of(0, 1), context -> {
            firstShardDelivered.countDown();
            return DeliveryResult.success();
        })) {
            safe.add(1, 0);
            safe.add(2, 1);
            safe.response = (command, records) -> {
                if (command.getQueueKey().shardSlot() == 1) {
                    blockedShardEntered.countDown();
                    await(unblockShard);
                    throw new OutboxStorageException("OUTBOX_CLAIM_TRANSACTION_INDETERMINATE");
                }
                return SafeHarness.batch(records);
            };
            safe.start();
            safe.subject.submitDue();
            assertThat(blockedShardEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstShardDelivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(safe.delivered).containsExactly("safe-1");
            assertThat(unblockShard.getCount()).isEqualTo(1);
            unblockShard.countDown();
        } finally {
            unblockShard.countDown();
        }
    }

    @Test
    void a_claim_commit_with_unknown_result_is_never_delivered_from_unconfirmed_records() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try (SafeHarness safe = new SafeHarness(1, List.of(0), context -> {
            delivered.countDown();
            return DeliveryResult.success();
        })) {
            safe.add(1, 0);
            safe.response = (command, records) -> {
                attempted.countDown();
                throw new OutboxStorageException("OUTBOX_CLAIM_TRANSACTION_INDETERMINATE");
            };
            safe.start();
            safe.subject.submitDue();
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered.await(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(safe.finishes).isEmpty();
        }
    }

    @Test
    void indeterminate_ack_retries_the_same_result_without_running_the_handler_again() throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        DeliveryResult actualResult = DeliveryResult.retryableFailure("TEMP", "retry later");
        try (SafeHarness safe = new SafeHarness(1, List.of(0), context -> actualResult)) {
            safe.add(1, 0);
            safe.finishResults.add(OutboxFinishStatusEnum.INDETERMINATE);
            safe.onApplied = applied::countDown;
            safe.start();
            safe.subject.submitDue();
            assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(safe.delivered).containsExactly("safe-1");
            assertThat(safe.finishes).hasSize(2).allSatisfy(command -> {
                assertThat(command.getDeliveryResult()).isEqualTo(actualResult);
                assertThat(command.getMessageId()).isEqualTo("safe-1");
                assertThat(command.getAttemptCount()).isEqualTo(1);
                assertThat(command.getClaimToken()).isEqualTo(safe.finishes.getFirst().getClaimToken());
            });
            org.mockito.Mockito.verifyNoInteractions(safe.lifecycle);
        }
    }

    @Test
    void an_expired_message_lease_does_not_start_a_handler() throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try (SafeHarness safe = new SafeHarness(1, List.of(0), context -> {
            delivered.countDown();
            return DeliveryResult.success();
        })) {
            safe.add(1, 0);
            safe.response = (command, rows) -> {
                claimed.countDown();
                return SafeHarness.batch(rows.stream().map(row -> SafeHarness.claimed(row, command,
                        Instant.now().minusMillis(1))).toList());
            };
            safe.start();
            safe.subject.submitDue();
            assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered.await(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(safe.finishes).isEmpty();
        }
    }

    @Test
    void a_failed_handler_does_not_remove_the_only_ready_worker() throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        try (SafeHarness safe = new SafeHarness(1, List.of(0), context -> {
            if ("safe-1".equals(context.messageId())) {
                throw new AssertionError("isolated handler error");
            }
            return DeliveryResult.success();
        })) {
            safe.add(1, 0);
            safe.add(2, 0);
            safe.onApplied = applied::countDown;
            safe.start();
            safe.subject.submitDue();
            assertThat(applied.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(safe.delivered).containsExactly("safe-1", "safe-2");
            assertThat(safe.finishes).extracting(OutboxFinishCommand::getMessageId).containsExactly("safe-2");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test barrier was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @FunctionalInterface
    private interface SafeDelivery {
        DeliveryResult deliver(DeliveryContext context) throws InterruptedException;
    }

    private static final class SafeHarness implements AutoCloseable {
        final OutboxStore mockStore = mock(OutboxStore.class);
        final OutboxLifecycleService lifecycle = mock(OutboxLifecycleService.class);
        final ThreadPoolTaskExecutor workers = new ThreadPoolTaskExecutor();
        final TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        final Map<Integer, ConcurrentLinkedQueue<OutboxRecord>> pending = new ConcurrentHashMap<>();
        final List<OutboxClaimCommand> commands = new CopyOnWriteArrayList<>();
        final List<OutboxFinishCommand> finishes = new CopyOnWriteArrayList<>();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<OutboxFinishStatusEnum> finishResults = new ConcurrentLinkedQueue<>();
        final AtomicInteger claimed = new AtomicInteger();
        final OutboxDispatcher subject;
        volatile BiFunction<OutboxClaimCommand, List<OutboxRecord>, OutboxClaimBatchBO> response = (command, rows) -> batch(rows);
        volatile Runnable onApplied = () -> { };

        SafeHarness(int concurrency, List<Integer> slots, SafeDelivery delivery) {
            properties.getDispatch().setMode(DispatchMode.LOCAL).setMaxClaimBatch(1).setMaxConcurrentShards(2);
            properties.getPolling().setConcurrency(concurrency);
            properties.getPolling().setBatchSize(10);
            properties.getPolling().setFixedDelay(Duration.ofHours(1));
            workers.setCorePoolSize(concurrency);
            workers.setMaxPoolSize(concurrency);
            workers.setQueueCapacity(0);
            workers.setThreadNamePrefix("outbox-safe-test-");
            workers.setAwaitTerminationSeconds(2);
            workers.initialize();
            when(mockStore.dispatchSlots()).thenReturn(slots);
            when(mockStore.supportsDispatchMode(any())).thenReturn(true);
            when(mockStore.claim(any())).thenAnswer(invocation -> {
                OutboxClaimCommand command = invocation.getArgument(0);
                commands.add(command);
                List<OutboxRecord> rows = new ArrayList<>();
                var available = pending.computeIfAbsent(command.getQueueKey().shardSlot(), ignored -> new ConcurrentLinkedQueue<>());
                while (rows.size() < command.getLimit()) {
                    OutboxRecord row = available.poll();
                    if (row == null) break;
                    rows.add(claimed(row, command, Instant.now().plusSeconds(60)));
                }
                claimed.addAndGet(rows.size());
                return response.apply(command, List.copyOf(rows));
            });
            when(mockStore.finishDelivery(any())).thenAnswer(invocation -> {
                OutboxFinishCommand command = invocation.getArgument(0);
                finishes.add(command);
                OutboxFinishStatusEnum result = finishResults.poll();
                if (result == null) result = OutboxFinishStatusEnum.APPLIED;
                if (result == OutboxFinishStatusEnum.APPLIED) onApplied.run();
                return result;
            });
            DeliveryHandler handler = new DeliveryHandler() {
                @Override public String channel() { return "http"; }
                @Override public void validateDestination(String destination) { }
                @Override public DeliveryResult deliver(DeliveryContext context) throws InterruptedException {
                    delivered.add(context.messageId());
                    return delivery.deliver(context);
                }
            };
            subject = new OutboxDispatcher(mockStore, new DeliveryHandlerRegistry(List.of(handler)),
                    new DefaultDeliveryFailureClassifier(), attempt -> Duration.ofMillis(1),
                    new OutboxDeadLetterNotifier(List.of()), new NoopOutboxMetrics(), new OutboxWorkerIdentity("safe-test"),
                    workers, properties, Clock.systemUTC(), lifecycle, new OutboxMpStorageProperties());
        }

        void add(long id, int slot) {
            Instant now = Instant.now();
            pending.computeIfAbsent(slot, ignored -> new ConcurrentLinkedQueue<>()).add(new OutboxRecord(
                    id, "safe-" + id, "key-" + id, "a".repeat(64), "http", "orders", "{}", "application/json",
                    null, Map.of(), null, OutboxStatus.PROCESSING, 1, 3, now, null, null, null, null,
                    now, now, null, slot));
        }

        void start() throws Exception {
            Class<?> coordinator = Class.forName("top.egon.cola.component.outbox.dispatch.OutboxQueueCoordinator");
            try {
                OutboxDispatcher.class.getMethod("startSafeDispatch", coordinator).invoke(subject, new Object[]{null});
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }

        static OutboxClaimBatchBO batch(List<OutboxRecord> rows) {
            return OutboxClaimBatchBO.builder().databaseTime(Instant.now()).records(rows).build();
        }

        void stop() throws Exception {
            OutboxDispatcher.class.getMethod("stopSafeDispatch").invoke(subject);
        }

        static OutboxRecord claimed(OutboxRecord row, OutboxClaimCommand command, Instant deadline) {
            return new OutboxRecord(row.id(), row.messageId(), row.idempotencyKey(), row.messageFingerprint(),
                    row.channel(), row.destination(), row.payload(), row.contentType(), row.schemaVersion(), row.headers(),
                    row.traceId(), row.status(), row.attemptCount(), row.maxAttempts(), row.nextAttemptAt(),
                    command.getClaimToken(), deadline, null, null, row.createdAt(), row.updatedAt(), null,
                    command.getQueueKey().shardSlot());
        }

        @Override
        public void close() throws Exception {
            try {
                OutboxDispatcher.class.getMethod("stopSafeDispatch").invoke(subject);
            } catch (NoSuchMethodException absentBeforeImplementation) {
                // The RED fixture still owns and closes the real executor.
            } finally {
                workers.shutdown();
            }
        }
    }

    private OutboxDispatcher dispatcher(
            DeliveryHandlerRegistry registry,
            TransactionalOutboxProperties properties
    ) {
        return dispatcher(registry, properties, new OutboxMpStorageProperties());
    }

    private OutboxDispatcher dispatcher(
            DeliveryHandlerRegistry registry,
            TransactionalOutboxProperties properties,
            OutboxMpStorageProperties storageProperties
    ) {
        return new OutboxDispatcher(
                store,
                registry,
                new DefaultDeliveryFailureClassifier(),
                attempt -> Duration.ofSeconds(attempt),
                new OutboxDeadLetterNotifier(List.of(deadEvents::add)),
                metrics,
                new OutboxWorkerIdentity("node-a"),
                task -> {
                    taskSubmissions.incrementAndGet();
                    task.run();
                },
                properties,
                Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC),
                lifecycleService,
                storageProperties
        );
    }

    private OutboxRecord record(long id, String messageId, String channel, int attempt, int max) {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        return new OutboxRecord(
                id,
                messageId,
                "key-" + messageId,
                "fingerprint",
                channel,
                "orders",
                "{}",
                "application/json",
                null,
                Map.of(),
                "trace-1",
                OutboxStatus.PROCESSING,
                attempt,
                max,
                now,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }

    private static final class RecordingDeliveryHandler implements DeliveryHandler {

        private final Map<String, DeliveryResult> results = new LinkedHashMap<>();
        private final Map<String, Error> errors = new LinkedHashMap<>();
        private int deliveryCount;

        @Override
        public String channel() {
            return "http";
        }

        @Override
        public void validateDestination(String destination) {
        }

        @Override
        public DeliveryResult deliver(DeliveryContext context) {
            deliveryCount++;
            Error error = errors.get(context.messageId());
            if (error != null) {
                throw error;
            }
            return results.get(context.messageId());
        }
    }

    private static final class RecordingMetrics implements OutboxMetrics {

        private int leaseLostCount;
        private int retryCount;
        private int deadCount;
        private int deliveryCount;

        @Override
        public void enqueue(boolean created) {
        }

        @Override
        public void claimed(int count) {
        }

        @Override
        public void delivery(String channel, String result, Duration duration) {
            deliveryCount++;
        }

        @Override
        public void retry(String channel) {
            retryCount++;
        }

        @Override
        public void dead(String channel) {
            deadCount++;
        }

        @Override
        public void leaseLost() {
            leaseLostCount++;
        }

        @Override
        public void wakeupRejected() {
        }

        @Override
        public void updateBacklog(long value) {
        }
    }

    private static final class RecordingOutboxStore implements OutboxStore {

        private final List<OutboxRecord> records = new ArrayList<>();
        private final Map<Long, String> messageIds = new LinkedHashMap<>();
        private final List<String> succeeded = new ArrayList<>();
        private final Map<String, Duration> retried = new LinkedHashMap<>();
        private final List<String> dead = new ArrayList<>();
        private final Map<String, String> deadCodes = new LinkedHashMap<>();
        private boolean allowOwnerUpdate = true;
        private int claimDueCount;
        private int claimByMessageIdsCount;
        private Collection<String> lastClaimDueChannels;
        private Collection<String> lastClaimByIdsChannels;
        private boolean filterByChannels;
        private int backlogCount;
        private int recoverCount;
        private Duration recoverDelay;
        private boolean failRecovery;

        void add(OutboxRecord... added) {
            records.addAll(List.of(added));
            for (OutboxRecord record : added) {
                messageIds.put(record.id(), record.messageId());
            }
        }

        @Override
        public OutboxReceipt enqueue(NewOutboxRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OutboxRecord> claimDue(int limit, String leaseOwner, Duration leaseDuration) {
            claimDueCount++;
            return records.stream().limit(limit).map(record -> withOwner(record, leaseOwner)).toList();
        }

        @Override
        public List<OutboxRecord> claimDue(
                Collection<String> channels,
                int limit,
                String leaseOwner,
                Duration leaseDuration
        ) {
            lastClaimDueChannels = channels == null ? null : Set.copyOf(channels);
            claimDueCount++;
            if (filterByChannels && channels != null && channels.isEmpty()) {
                return List.of();
            }
            return records.stream()
                    .filter(record -> !filterByChannels || channels == null || channels.contains(record.channel()))
                    .limit(limit)
                    .map(record -> withOwner(record, leaseOwner))
                    .toList();
        }

        @Override
        public List<OutboxRecord> claimByMessageIds(
                Collection<String> requestedIds,
                int limit,
                String leaseOwner,
                Duration leaseDuration
        ) {
            claimByMessageIdsCount++;
            return records.stream()
                    .filter(record -> requestedIds.contains(record.messageId()))
                    .limit(limit)
                    .map(record -> withOwner(record, leaseOwner))
                    .toList();
        }

        @Override
        public List<OutboxRecord> claimByMessageIds(
                Collection<String> requestedIds,
                Collection<String> channels,
                int limit,
                String leaseOwner,
                Duration leaseDuration
        ) {
            lastClaimByIdsChannels = channels == null ? null : Set.copyOf(channels);
            claimByMessageIdsCount++;
            if (filterByChannels && channels != null && channels.isEmpty()) {
                return List.of();
            }
            return records.stream()
                    .filter(record -> requestedIds.contains(record.messageId()))
                    .filter(record -> !filterByChannels || channels == null || channels.contains(record.channel()))
                    .limit(limit)
                    .map(record -> withOwner(record, leaseOwner))
                    .toList();
        }

        @Override
        public boolean markSucceeded(long id, String leaseOwner) {
            if (allowOwnerUpdate) {
                succeeded.add(messageIds.get(id));
            }
            return allowOwnerUpdate;
        }

        @Override
        public boolean markRetry(
                long id,
                String leaseOwner,
                Duration delay,
                String errorCode,
                String errorMessage
        ) {
            if (allowOwnerUpdate) {
                retried.put(messageIds.get(id), delay);
            }
            return allowOwnerUpdate;
        }

        @Override
        public boolean markDead(
                long id,
                String leaseOwner,
                String errorCode,
                String errorMessage
        ) {
            if (allowOwnerUpdate) {
                String messageId = messageIds.get(id);
                dead.add(messageId);
                deadCodes.put(messageId, errorCode);
            }
            return allowOwnerUpdate;
        }

        @Override
        public int deleteSucceeded(Duration retention, int limit) {
            return 0;
        }

        @Override
        public int recoverExhausted(int limit, Duration cooldown) {
            recoverCount++;
            recoverDelay = cooldown;
            if (failRecovery) throw new IllegalStateException("recovery failed");
            return 0;
        }

        @Override
        public long countBacklog() {
            backlogCount++;
            return records.size();
        }

        @Override
        public void validateSchema() {
        }

        private OutboxRecord withOwner(OutboxRecord record, String leaseOwner) {
            return new OutboxRecord(
                    record.id(),
                    record.messageId(),
                    record.idempotencyKey(),
                    record.messageFingerprint(),
                    record.channel(),
                    record.destination(),
                    record.payload(),
                    record.contentType(),
                    record.schemaVersion(),
                    record.headers(),
                    record.traceId(),
                    record.status(),
                    record.attemptCount(),
                    record.maxAttempts(),
                    record.nextAttemptAt(),
                    leaseOwner,
                    record.lockedUntil(),
                    record.lastErrorCode(),
                    record.lastErrorMessage(),
                    record.createdAt(),
                    record.updatedAt(),
                    record.completedAt()
            );
        }
    }
}
