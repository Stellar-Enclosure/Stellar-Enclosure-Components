package top.egon.cola.component.outbox.dispatch;

import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Objects;
import java.util.Comparator;
import top.egon.cola.component.outbox.store.OutboxQueueLease;
import top.egon.cola.component.outbox.store.OutboxQueueKey;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.store.OutboxFinishCommand;
import top.egon.cola.component.outbox.store.OutboxClaimCommand;
import top.egon.cola.component.outbox.store.OutboxClaimBatchBO;
import top.egon.cola.component.outbox.delivery.statemachine.BusinessStateMachineDeliveryHandler;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import org.springframework.aop.support.AopUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.common.exception.OutboxStateMachineException;
import top.egon.cola.component.outbox.deadletter.OutboxDeadLetterNotifier;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryFailureClassifier;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.event.OutboxDeadLetterEvent;
import top.egon.cola.component.outbox.observability.OutboxMetrics;
import top.egon.cola.component.outbox.retry.OutboxRetryPolicy;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleSignalEnum;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStatus;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final String HANDLER_MISSING = "OUTBOX_HANDLER_MISSING";
    private static final String DESTINATION_INVALID = "OUTBOX_DESTINATION_INVALID";
    private static final String RETRY_EXHAUSTED = "OUTBOX_RETRY_EXHAUSTED";
    private static final Map<DeliveryResult.Kind, OutboxLifecycleSignalEnum> SIGNALS_BY_RESULT =
            createSignalsByResult();
    private static final Map<OutboxLifecycleSignalEnum, Set<OutboxStatus>> ALLOWED_TARGETS_BY_SIGNAL = Map.of(
            OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, Set.of(OutboxStatus.SUCCEEDED),
            OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE, Set.of(OutboxStatus.RETRY_WAIT, OutboxStatus.DEAD),
            OutboxLifecycleSignalEnum.DELIVERY_PERMANENT, Set.of(OutboxStatus.DEAD)
    );
    private static final Map<OutboxStatus, OutboxLifecycleTargetAction> TARGET_ACTIONS = createTargetActions();

    private static Map<DeliveryResult.Kind, OutboxLifecycleSignalEnum> createSignalsByResult() {
        EnumMap<DeliveryResult.Kind, OutboxLifecycleSignalEnum> signals =
                new EnumMap<>(DeliveryResult.Kind.class);
        signals.put(DeliveryResult.Kind.SUCCESS, OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED);
        signals.put(DeliveryResult.Kind.RETRYABLE_FAILURE, OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE);
        signals.put(DeliveryResult.Kind.PERMANENT_FAILURE, OutboxLifecycleSignalEnum.DELIVERY_PERMANENT);
        return Collections.unmodifiableMap(signals);
    }

    private static Map<OutboxStatus, OutboxLifecycleTargetAction> createTargetActions() {
        EnumMap<OutboxStatus, OutboxLifecycleTargetAction> actions = new EnumMap<>(OutboxStatus.class);
        actions.put(OutboxStatus.SUCCEEDED, OutboxDispatcher::markSucceededTarget);
        actions.put(OutboxStatus.RETRY_WAIT, OutboxDispatcher::markRetryTarget);
        actions.put(OutboxStatus.DEAD, OutboxDispatcher::markDeadTarget);
        return Collections.unmodifiableMap(actions);
    }

    @Qualifier("outboxStore")
    private final OutboxStore store;
    @Qualifier("deliveryHandlerRegistry")
    private final DeliveryHandlerRegistry handlerRegistry;
    @Qualifier("deliveryFailureClassifier")
    private final DeliveryFailureClassifier failureClassifier;
    @Qualifier("outboxRetryPolicy")
    private final OutboxRetryPolicy retryPolicy;
    @Qualifier("outboxDeadLetterNotifier")
    private final OutboxDeadLetterNotifier deadLetterNotifier;
    @Qualifier("outboxMetrics")
    private final OutboxMetrics metrics;
    @Qualifier("outboxWorkerIdentity")
    private final OutboxWorkerIdentity workerIdentity;
    @Qualifier("outboxDeliveryExecutor")
    private final TaskExecutor taskExecutor;
    @Qualifier("outboxStateMachineOutboxProperties")
    private final TransactionalOutboxProperties properties;
    @Qualifier("outboxStateMachineClock")
    private final Clock clock;
    @Qualifier("outboxLifecycleService")
    private final OutboxLifecycleService lifecycleService;
    @Qualifier("outboxMpStorageProperties")
    private final OutboxMpStorageProperties storageProperties;
    private volatile Semaphore deliveryPermits;
    private final AtomicBoolean coordinatorScheduled = new AtomicBoolean();
    private final AtomicBoolean fullPollRequested = new AtomicBoolean();
    private final Object pendingMonitor = new Object();
    private final Set<String> pendingMessageIds = new LinkedHashSet<>();

    @PostConstruct
    void initialize() {
        initializeDeliveryPermits();
    }

    private Semaphore deliveryPermits() {
        Semaphore permits = deliveryPermits;
        if (permits == null) {
            initializeDeliveryPermits();
            permits = deliveryPermits;
        }
        return permits;
    }

    private synchronized void initializeDeliveryPermits() {
        if (deliveryPermits != null) {
            return;
        }
        int capacity = Math.max(
                1,
                properties.getPolling().getConcurrency()
                        + properties.getDelivery().getQueueCapacity() - 1
        );
        this.deliveryPermits = new Semaphore(capacity);
    }

    public void submitDue() {
        if (!canConsume()) {
            return;
        }
        if (safeMode()) {
            signalSafeDispatch();
            return;
        }
        fullPollRequested.set(true);
        scheduleCoordinator();
    }

    public void submitMessageIds(Collection<String> messageIds) {
        if (!canConsume()) {
            return;
        }
        if (safeMode()) {
            signalSafeDispatch();
            return;
        }
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        synchronized (pendingMonitor) {
            for (String messageId : messageIds) {
                if (pendingMessageIds.size() >= properties.getPolling().getBatchSize()) {
                    fullPollRequested.set(true);
                    break;
                }
                pendingMessageIds.add(messageId);
            }
        }
        scheduleCoordinator();
    }

    void dispatchDue() {
        if (!canConsume()) {
            return;
        }
        if (safeMode()) {
            signalSafeDispatch();
            return;
        }
        if (properties.getRetry().isRecoverExhausted()) {
            if (!workerIdentity.canClaim()) {
                return;
            }
            try {
                store.recoverExhausted(properties.getPolling().getBatchSize(), properties.getRetry().getExhaustedRetryDelay());
            } catch (RuntimeException failure) {
                log.warn("Transactional outbox exhausted-message recovery failed; failureType={}", failure.getClass().getSimpleName());
            }
        }
        dispatch(limit -> store.claimDue(
                handlerRegistry.channels(),
                limit,
                workerIdentity.nextLeaseOwner(),
                properties.getDelivery().getLeaseDuration()
        ));
    }

    void dispatchMessageIds(Collection<String> messageIds) {
        if (!canConsume()) {
            return;
        }
        if (safeMode()) {
            signalSafeDispatch();
            return;
        }
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        List<String> selectedIds = List.copyOf(messageIds);
        dispatch(limit -> store.claimByMessageIds(
                selectedIds,
                handlerRegistry.channels(),
                limit,
                workerIdentity.nextLeaseOwner(),
                properties.getDelivery().getLeaseDuration()
        ));
    }

    private void scheduleCoordinator() {
        if (!coordinatorScheduled.compareAndSet(false, true)) {
            return;
        }
        AtomicBoolean started = new AtomicBoolean();
        try {
            taskExecutor.execute(() -> {
                started.set(true);
                runCoordinator();
            });
        } catch (RuntimeException exception) {
            if (started.get()) {
                throw exception;
            }
            coordinatorScheduled.set(false);
            metrics.wakeupRejected();
            log.warn("Transactional outbox coordinator submission was rejected");
        }
    }

    private void runCoordinator() {
        try {
            boolean fullPoll = fullPollRequested.getAndSet(false);
            List<String> messageIds;
            synchronized (pendingMonitor) {
                messageIds = new ArrayList<>(pendingMessageIds);
                pendingMessageIds.clear();
            }
            if (fullPoll) {
                dispatchDue();
            } else {
                dispatchMessageIds(messageIds);
            }
        } finally {
            coordinatorScheduled.set(false);
            if (hasPendingWork()) {
                scheduleCoordinator();
            }
        }
    }

    private boolean hasPendingWork() {
        if (fullPollRequested.get()) {
            return true;
        }
        synchronized (pendingMonitor) {
            return !pendingMessageIds.isEmpty();
        }
    }

    private void dispatch(ClaimOperation claimOperation) {
        if (!workerIdentity.canClaim()) {
            return;
        }
        int reservedPermits = reservePermits();
        if (reservedPermits == 0) {
            return;
        }
        List<OutboxRecord> records;
        try {
            records = claimOperation.claim(reservedPermits);
        } catch (RuntimeException | Error failure) {
            deliveryPermits().release(reservedPermits);
            throw failure;
        }

        int unusedPermits = reservedPermits - records.size();
        if (unusedPermits > 0) {
            deliveryPermits().release(unusedPermits);
        }
        metrics.claimed(records.size());
        try {
            for (OutboxRecord record : records) {
                submitDelivery(record);
            }
        } finally {
            updateBacklog();
        }
    }

    private int reservePermits() {
        int reserved = 0;
        int batchSize = properties.getPolling().getBatchSize();
        while (reserved < batchSize && deliveryPermits().tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    private void submitDelivery(OutboxRecord record) {
        AtomicBoolean started = new AtomicBoolean();
        try {
            taskExecutor.execute(() -> {
                started.set(true);
                try {
                    deliver(record);
                } finally {
                    deliveryPermits().release();
                }
            });
        } catch (RuntimeException exception) {
            if (started.get()) {
                throw exception;
            }
            deliveryPermits().release();
            log.warn("Transactional outbox delivery submission was rejected");
        }
    }

    private void deliver(OutboxRecord record) {
        Instant startedAt = clock.instant();
        String metricResult = "error";
        DeliveryResult result;
        try {
            DeliveryHandler handler;
            try {
                handler = handlerRegistry.required(record.channel());
            } catch (RuntimeException exception) {
                result = DeliveryResult.permanentFailure(HANDLER_MISSING, HANDLER_MISSING);
                metricResult = metricResult(result);
                applyResult(record, result);
                return;
            }
            try {
                handler.validateDestination(record.destination());
            } catch (RuntimeException exception) {
                result = DeliveryResult.permanentFailure(
                        DESTINATION_INVALID,
                        DESTINATION_INVALID
                );
                metricResult = metricResult(result);
                applyResult(record, result);
                return;
            }
            DeliveryContext context = new DeliveryContext(
                    record.messageId(),
                    record.channel(),
                    record.destination(),
                    record.payload(),
                    record.contentType(),
                    record.schemaVersion(),
                    record.headers(),
                    record.traceId(),
                    record.attemptCount(),
                    record.maxAttempts(),
                    clock.instant().plus(properties.getDelivery().getTimeout())
            );
            try {
                result = handler.deliver(context);
            } catch (Exception exception) {
                result = failureClassifier.classify(exception);
            }
            if (result == null) {
                result = DeliveryResult.permanentFailure(
                        "OUTBOX_INVALID_DELIVERY_RESULT",
                        "OUTBOX_INVALID_DELIVERY_RESULT"
                );
            }
            metricResult = metricResult(result);
            applyResult(record, result);
        } catch (Error error) {
            log.error(
                    "Fatal transactional outbox delivery error for internal record {}",
                    record.id()
            );
            throw error;
        } finally {
            Duration duration = Duration.between(startedAt, clock.instant());
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }
            metrics.delivery(record.channel(), metricResult, duration);
        }
    }

    private String metricResult(DeliveryResult result) {
        return result.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    private void applyResult(OutboxRecord record, DeliveryResult result) {
        OutboxLifecycleSignalEnum signal = SIGNALS_BY_RESULT.get(result.kind());
        if (signal == null) {
            throw new OutboxStateMachineException(
                    "OUTBOX_FSM_EXECUTION_FAILED",
                    false,
                    "No outbox lifecycle signal is registered for delivery result " + result.kind()
            );
        }
        OutboxStatus target = lifecycleService.evaluate(
                OutboxStatus.PROCESSING.getMessage(),
                signal,
                record.attemptCount(),
                record.maxAttempts()
        );
        if (target == null || !ALLOWED_TARGETS_BY_SIGNAL.get(signal).contains(target)) {
            throw unexpectedTarget(signal, target);
        }
        OutboxLifecycleTargetAction action = TARGET_ACTIONS.get(target);
        if (action == null) {
            throw unexpectedTarget(signal, target);
        }
        action.apply(this, record, result, signal);
    }

    private void markSucceededTarget(
            OutboxRecord record,
            DeliveryResult result,
            OutboxLifecycleSignalEnum signal
    ) {
        transition(store.markSucceeded(record.id(), record.lockedBy(), record.shardSlot()), record);
    }

    private void markRetryTarget(
            OutboxRecord record,
            DeliveryResult result,
            OutboxLifecycleSignalEnum signal
    ) {
        boolean updated = store.markRetry(
                record.id(),
                record.lockedBy(),
                retryPolicy.nextDelay(record.attemptCount()),
                sanitize(result.code(), 64),
                sanitize(result.message(), 512),
                record.shardSlot()
        );
        if (transition(updated, record)) {
            metrics.retry(record.channel());
        }
    }

    private void markDeadTarget(
            OutboxRecord record,
            DeliveryResult result,
            OutboxLifecycleSignalEnum signal
    ) {
        boolean exhausted = signal == OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE;
        String code = exhausted ? RETRY_EXHAUSTED : sanitize(result.code(), 64);
        if (!exhausted && RETRY_EXHAUSTED.equals(code)) code = "OUTBOX_PERMANENT_FAILURE";
        markDead(
                record,
                code,
                sanitize(exhausted ? result.code() : result.message(), 512)
        );
    }

    private static OutboxStateMachineException unexpectedTarget(
            OutboxLifecycleSignalEnum signal,
            OutboxStatus target
    ) {
        return new OutboxStateMachineException(
                "OUTBOX_FSM_EXECUTION_FAILED",
                false,
                "Unexpected outbox lifecycle target " + target + " for signal " + signal
        );
    }

    private void markDead(OutboxRecord record, String errorCode, String errorMessage) {
        boolean updated = store.markDead(
                record.id(),
                record.lockedBy(),
                errorCode,
                errorMessage,
                record.shardSlot()
        );
        if (!transition(updated, record)) {
            return;
        }
        metrics.dead(record.channel());
        deadLetterNotifier.notifyDead(new OutboxDeadLetterEvent(
                record.messageId(),
                record.channel(),
                record.destination(),
                record.attemptCount(),
                errorCode,
                errorMessage,
                record.traceId()
        ));
    }

    private boolean transition(boolean updated, OutboxRecord record) {
        if (!updated) {
            metrics.leaseLost();
            log.warn(
                    "Transactional outbox lease ownership was lost for internal record {}",
                    record.id()
            );
        }
        return updated;
    }

    private void updateBacklog() {
        try {
            metrics.updateBacklog(store.countBacklog());
        } catch (RuntimeException exception) {
            log.warn("Transactional outbox backlog measurement failed");
        }
    }

    private String sanitize(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maximumLength));
        value.codePoints().forEach(codePoint -> {
            int width = Character.charCount(codePoint);
            if (width <= maximumLength - sanitized.length()) {
                sanitized.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            }
        });
        return sanitized.toString();
    }

    @FunctionalInterface
    private interface OutboxLifecycleTargetAction {

        void apply(
                OutboxDispatcher dispatcher,
                OutboxRecord record,
                DeliveryResult result,
                OutboxLifecycleSignalEnum signal
        );
    }

    @FunctionalInterface
    private interface ClaimOperation {

        List<OutboxRecord> claim(int limit);
    }

    private volatile ReadyDispatchRuntime readyRuntime;

    public synchronized void startSafeDispatch(OutboxQueueCoordinator coordinator) {
        if (!safeMode() || !canConsume() || readyRuntime != null) {
            return;
        }
        DispatchMode mode = properties.getDispatch().getMode();
        if (!store.supportsDispatchMode(mode)) {
            throw new IllegalStateException("OUTBOX_STORE_DISPATCH_MODE_UNSUPPORTED");
        }
        if (mode == DispatchMode.COORDINATED && coordinator == null) {
            throw new IllegalStateException("OUTBOX_QUEUE_COORDINATOR_REQUIRED");
        }
        ReadyDispatchRuntime runtime = new ReadyDispatchRuntime(mode, coordinator);
        readyRuntime = runtime;
        runtime.start();
    }

    public synchronized void stopSafeDispatch() {
        ReadyDispatchRuntime runtime = readyRuntime;
        if (runtime != null) {
            runtime.stop();
            readyRuntime = null;
        }
    }

    private boolean safeMode() {
        return properties.getDispatch().getMode() != DispatchMode.LEGACY;
    }

    private boolean canConsume() {
        return properties.getDispatch().isConsumptionEnabled() && !storageProperties.isMigrationMode();
    }

    private void signalSafeDispatch() {
        ReadyDispatchRuntime runtime = readyRuntime;
        if (runtime != null) {
            runtime.signal();
        }
    }

    private final class ReadyDispatchRuntime {

        private final DispatchMode mode;
        private final OutboxQueueCoordinator coordinator;
        private final List<Integer> slots;
        private final int parallelShards;
        private final ScheduledExecutorService controller = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("outbox-dispatch-", 0).factory());
        private final ScheduledExecutorService heartbeat;
        private final ExecutorService claims;
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean tickEnqueued = new AtomicBoolean();
        private final AtomicLong signals = new AtomicLong();
        private final ConcurrentLinkedQueue<ReadyWorker> ready = new ConcurrentLinkedQueue<>();
        private final Set<Integer> claiming = ConcurrentHashMap.newKeySet();
        private final Map<OutboxQueueKey, Long> retryAfter = new ConcurrentHashMap<>();
        private final Map<OutboxQueueKey, Integer> emptyStreak = new ConcurrentHashMap<>();
        private final List<ReadyWorker> workers = new ArrayList<>();
        private int cursor;
        private final AtomicInteger opportunity = new AtomicInteger();

        ReadyDispatchRuntime(DispatchMode mode, OutboxQueueCoordinator coordinator) {
            this.mode = mode;
            this.coordinator = coordinator;
            this.slots = List.copyOf(store.dispatchSlots());
            if (slots.isEmpty()) {
                throw new IllegalStateException("OUTBOX_DISPATCH_SHARDS_REQUIRED");
            }
            this.parallelShards = Math.min(slots.size(), Math.min(properties.getPolling().getConcurrency(),
                    properties.getDispatch().getMaxConcurrentShards()));
            claims = Executors.newFixedThreadPool(parallelShards,
                    Thread.ofPlatform().daemon().name("outbox-claim-", 0).factory());
            heartbeat = coordinator == null ? null : Executors.newScheduledThreadPool(parallelShards,
                    Thread.ofPlatform().daemon().name("outbox-heartbeat-", 0).factory());
        }

        void start() {
            for (int index = 0; index < properties.getPolling().getConcurrency(); index++) {
                ReadyWorker worker = new ReadyWorker();
                try {
                    taskExecutor.execute(worker::run);
                    workers.add(worker);
                } catch (RuntimeException failure) {
                    metrics.wakeupRejected();
                    log.warn("Outbox worker did not start, failureType={}", failure.getClass().getSimpleName());
                }
            }
            if (heartbeat != null) {
                long interval = Math.max(1, properties.getCoordination().getHeartbeatInterval().toMillis());
                for (int slot : slots) {
                    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, interval / 5));
                    heartbeat.scheduleWithFixedDelay(() -> {
                        if (accepting.get() && canConsume()) {
                            coordinator.refresh(slot, key -> claiming.contains(key.shardSlot()));
                            signal();
                        }
                    }, jitter, interval + jitter, TimeUnit.MILLISECONDS);
                }
            }
            signal();
        }

        void signal() {
            if (!accepting.get() || !canConsume()) {
                return;
            }
            signals.incrementAndGet();
            if (tickEnqueued.compareAndSet(false, true)) {
                try {
                    controller.execute(this::runTick);
                } catch (RejectedExecutionException stopped) {
                    tickEnqueued.set(false);
                }
            }
        }

        private void runTick() {
            long observed = signals.get();
            try {
                dispatchReady();
            } finally {
                tickEnqueued.set(false);
                if (signals.get() != observed) {
                    signal();
                }
            }
        }

        private void dispatchReady() {
            if (!accepting.get() || !canConsume() || ready.isEmpty() || !workerIdentity.canClaim()) {
                return;
            }
            List<OutboxQueueKey> candidates = runnableQueues();
            if (candidates.isEmpty()) {
                return;
            }
            int first = Math.floorMod(cursor, candidates.size());
            for (int index = 0; index < candidates.size() && claiming.size() < parallelShards; index++) {
                OutboxQueueKey key = candidates.get((first + index) % candidates.size());
                if (!accepting.get() || ready.isEmpty()) {
                    break;
                }
                if (retryAfter.getOrDefault(key, 0L) - System.nanoTime() > 0 || !claiming.add(key.shardSlot())) {
                    continue;
                }
                OutboxQueueLease lease = mode == DispatchMode.COORDINATED
                        ? coordinator.owned(key.shardSlot()).get(key) : null;
                if (mode == DispatchMode.COORDINATED && lease == null) {
                    claiming.remove(key.shardSlot());
                    continue;
                }
                int requested = Math.min(properties.getDispatch().getMaxClaimBatch(),
                        candidates.size() > 1 ? Math.max(1, ready.size() / candidates.size()) : ready.size());
                List<ReadyWorker> reserved = reserve(requested);
                if (reserved.isEmpty()) {
                    claiming.remove(key.shardSlot());
                    break;
                }
                try {
                    claims.execute(() -> claimAndAssign(key, lease, reserved));
                } catch (RejectedExecutionException stopped) {
                    ready.addAll(reserved);
                    claiming.remove(key.shardSlot());
                }
                cursor = first + index + 1;
            }
        }

        private List<OutboxQueueKey> runnableQueues() {
            if (mode == DispatchMode.COORDINATED) {
                return slots.stream().flatMap(slot -> coordinator.owned(slot).keySet().stream())
                        .sorted(Comparator.comparingInt(OutboxQueueKey::shardSlot).thenComparing(OutboxQueueKey::channel))
                        .toList();
            }
            List<String> channels = handlerRegistry.channels().stream().sorted().toList();
            return slots.stream().flatMap(slot -> channels.stream().map(channel -> new OutboxQueueKey(slot, channel)))
                    .toList();
        }

        private List<ReadyWorker> reserve(int count) {
            List<ReadyWorker> reserved = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ReadyWorker worker = ready.poll();
                if (worker == null) {
                    break;
                }
                reserved.add(worker);
            }
            return reserved;
        }

        private void claimAndAssign(OutboxQueueKey key, OutboxQueueLease lease, List<ReadyWorker> reserved) {
            int assigned = 0;
            boolean committed = false;
            long started = System.nanoTime();
            try {
                if (!accepting.get() || !canConsume() || !workerIdentity.canClaim()) {
                    return;
                }
                int count = reserved.size();
                int recovery = count / 4 + (count % 4 > 0 && Math.floorMod(opportunity.getAndIncrement(), 4) == 0 ? 1 : 0);
                var command = OutboxClaimCommand.builder().queueKey(key).queueLease(lease)
                        .claimToken(workerIdentity.nextLeaseOwner()).leaseDuration(properties.getDelivery().getLeaseDuration())
                        .limit(count).dueQuota(count - recovery).reclaimQuota(recovery).build();
                OutboxClaimBatchBO batch = store.claim(command);
                committed = true;
                for (int i = 0; i < batch.getRecords().size(); i++) {
                    metrics.dispatchStage(key.shardSlot(), key.channel(), "claim_committed");
                }
                if (System.nanoTime() - started >= properties.getCoordination().getClaimTimeout().toNanos()) {
                    throw new IllegalStateException("OUTBOX_CLAIM_DEADLINE_EXCEEDED");
                }
                if (batch.isNotOwner() || batch.getRecords().isEmpty() && batch.getExhaustedCount() == 0) {
                    backOff(key);
                } else {
                    retryAfter.remove(key);
                    emptyStreak.remove(key);
                }
                if (batch.getRecords().size() > count) {
                    throw new IllegalStateException("OUTBOX_CLAIM_EXCEEDS_RESERVED_CAPACITY");
                }
                long committedNanos = System.nanoTime();
                for (OutboxRecord row : batch.getRecords()) {
                    if (!Objects.equals(row.channel(), key.channel()) || row.shardSlot() != key.shardSlot()
                            || !command.getClaimToken().equals(row.lockedBy())) {
                        throw new IllegalStateException("OUTBOX_CLAIM_RECEIPT_MISMATCH");
                    }
                    Assignment assignment = new Assignment(row, batch.getDatabaseTime(), started, committedNanos);
                    if (reserved.get(assigned).mailbox.offer(assignment)) {
                        assigned++;
                        metrics.dispatchStage(key.shardSlot(), key.channel(), "handoff");
                    } else {
                        throw new IllegalStateException("OUTBOX_RESERVED_WORKER_NOT_READY");
                    }
                }
            } catch (RuntimeException failure) {
                backOff(key);
                metrics.dispatchStage(key.shardSlot(), key.channel(), committed ? "handoff_failed" : "claim_unconfirmed");
                log.warn("Outbox claim not dispatched, slot={}, channel={}, failureType={}",
                        key.shardSlot(), key.channel(), failure.getClass().getSimpleName());
            } finally {
                for (int index = assigned; index < reserved.size(); index++) {
                    if (accepting.get()) {
                        ready.add(reserved.get(index));
                    }
                }
                claiming.remove(key.shardSlot());
                signal();
            }
        }

        private void backOff(OutboxQueueKey key) {
            long base = properties.getPolling().getFixedDelay().toNanos();
            int multiplier = emptyStreak.merge(key, 1, (previous, next) -> Math.min(5, previous + 1));
            long delay = base * multiplier;
            delay += (long) (delay * properties.getPolling().getJitter() * ThreadLocalRandom.current().nextDouble());
            retryAfter.put(key, System.nanoTime() + Math.min(base * 5, delay));
        }

        void stop() {
            if (!accepting.getAndSet(false)) {
                return;
            }
            controller.shutdownNow();
            if (heartbeat != null) {
                heartbeat.shutdown();
            }
            claims.shutdown();
            long end = System.nanoTime() + properties.getShutdown().getGracePeriod().toNanos();
            await(claims, end);
            if (heartbeat != null) {
                await(heartbeat, end);
                for (int slot : slots) {
                    if (System.nanoTime() >= end || claiming.contains(slot)) {
                        continue;
                    }
                    coordinator.release(slot);
                }
            }
            for (ReadyWorker worker : workers) {
                long remaining = end - System.nanoTime();
                if (remaining > 0) {
                    try {
                        worker.stopped.await(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        private void await(ExecutorService executor, long end) {
            try {
                executor.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private final class ReadyWorker {
            private final BlockingQueue<Assignment> mailbox = new ArrayBlockingQueue<>(1);
            private final CountDownLatch stopped = new CountDownLatch(1);

            void run() {
                try {
                    do {
                        ready.add(this);
                        signal();
                        Assignment item = null;
                        while (item == null && (accepting.get() || claiming.size() > 0 || !mailbox.isEmpty())) {
                            item = mailbox.poll(50, TimeUnit.MILLISECONDS);
                        }
                        if (item != null) {
                            deliverWithLease(item);
                        }
                    } while (accepting.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    ready.remove(this);
                    stopped.countDown();
                }
            }
        }
    }

    private record Assignment(OutboxRecord record, Instant databaseTime, long startedNanos, long committedNanos) { }

    private Duration leaseRemaining(Assignment assignment) {
        if (assignment.databaseTime() == null || assignment.record().lockedUntil() == null) {
            return Duration.ZERO;
        }
        return Duration.between(assignment.databaseTime(), assignment.record().lockedUntil())
                .minusNanos(System.nanoTime() - assignment.startedNanos());
    }

    private void deliverWithLease(Assignment assignment) {
        OutboxRecord row = assignment.record();
        Duration budget = leaseRemaining(assignment).minus(properties.getDispatch().getAckReserve());
        if (budget.isNegative() || budget.isZero()) {
            metrics.dispatchStage(row.shardSlot(), row.channel(), "lease_expired_before_handler");
            return;
        }
        metrics.dispatchStageDuration(row.shardSlot(), row.channel(), "claim_to_start",
                Duration.ofNanos(System.nanoTime() - assignment.committedNanos()));
        Duration timeout = properties.getDelivery().getTimeout();
        if (timeout.compareTo(budget) > 0) {
            timeout = budget;
        }
        long started = System.nanoTime();
        DeliveryResult result = invokeHandler(row, clock.instant().plus(timeout), true);
        metrics.delivery(row.channel(), metricResult(result), Duration.ofNanos(System.nanoTime() - started));
        var command = OutboxFinishCommand.builder().id(row.id()).shardSlot(row.shardSlot()).messageId(row.messageId())
                .attemptCount(row.attemptCount()).claimToken(row.lockedBy()).deliveryResult(result).build();
        long ackStarted = System.nanoTime();
        OutboxFinishStatusEnum finish = OutboxFinishStatusEnum.INDETERMINATE;
        for (int tryNumber = 0; tryNumber < 2 && leaseRemaining(assignment).isPositive(); tryNumber++) {
            try {
                finish = store.finishDelivery(command);
            } catch (RuntimeException failure) {
                finish = OutboxFinishStatusEnum.INDETERMINATE;
            }
            if (finish != OutboxFinishStatusEnum.INDETERMINATE) {
                break;
            }
        }
        metrics.dispatchStageDuration(row.shardSlot(), row.channel(), "result_to_ack",
                Duration.ofNanos(System.nanoTime() - ackStarted));
        metrics.dispatchStage(row.shardSlot(), row.channel(), finish == OutboxFinishStatusEnum.APPLIED
                ? "ack_confirmed" : finish.name().toLowerCase(java.util.Locale.ROOT));
    }

    private DeliveryResult invokeHandler(OutboxRecord record, Instant deadline, boolean safe) {
        DeliveryHandler handler;
        try {
            handler = handlerRegistry.required(record.channel());
        } catch (RuntimeException missing) {
            return DeliveryResult.permanentFailure(HANDLER_MISSING, HANDLER_MISSING);
        }
        if (!safe || AopUtils.getTargetClass(handler) != BusinessStateMachineDeliveryHandler.class) {
            try {
                handler.validateDestination(record.destination());
            } catch (RuntimeException rejected) {
                return DeliveryResult.permanentFailure(DESTINATION_INVALID, DESTINATION_INVALID);
            }
        }
        DeliveryContext context = new DeliveryContext(record.messageId(), record.channel(), record.destination(),
                record.payload(), record.contentType(), record.schemaVersion(), record.headers(), record.traceId(),
                record.attemptCount(), record.maxAttempts(), deadline);
        DeliveryResult result;
        try {
            if (safe) {
                metrics.dispatchStage(record.shardSlot(), record.channel(), "handler_started");
            }
            result = handler.deliver(context);
        } catch (Exception failure) {
            result = failureClassifier.classify(failure);
        }
        return result == null
                ? DeliveryResult.permanentFailure("OUTBOX_INVALID_DELIVERY_RESULT", "OUTBOX_INVALID_DELIVERY_RESULT")
                : result;
    }

}
