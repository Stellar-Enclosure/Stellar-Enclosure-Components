package top.egon.cola.component.outbox.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.persistence.converter.OutboxQueueLeaseConverter;
import top.egon.cola.component.outbox.persistence.converter.OutboxQueueMemberConverter;
import top.egon.cola.component.outbox.persistence.dao.OutboxQueueLeaseDAO;
import top.egon.cola.component.outbox.persistence.dao.OutboxQueueMemberDAO;
import top.egon.cola.component.outbox.persistence.po.OutboxQueueLeasePO;
import top.egon.cola.component.outbox.persistence.po.OutboxQueueMemberPO;
import top.egon.cola.component.outbox.retry.OutboxRetryPolicy;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.common.exception.OutboxValidationException;
import top.egon.cola.component.outbox.persistence.OutboxSchemaMetadataValidator;
import top.egon.cola.component.outbox.persistence.OutboxTechnicalContextExecutor;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;
import top.egon.cola.component.outbox.persistence.OutboxShardRouting;
import top.egon.cola.component.outbox.persistence.converter.OutboxMessageConverter;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.persistence.repository.OutboxMessageRepository;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleStateMachineFactory;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleSignalEnum;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.sql.SQLException;

/** MyBatis-Plus implementation of the existing public outbox storage contract. */
@Slf4j
@Validated
public class MybatisPlusOutboxStore implements OutboxStore {

    private static final String TECHNICAL_USER_ID = "system:outbox";
    private static final int MAX_MEMBERS = 1024;
    private static final int MAX_QUEUES = 4096;
    private static final int MAX_CANDIDATE_LIMIT = 10_000;
    private static final int DEFAULT_MAX_CLAIM_CONTENTION_RETRIES = 8;
    private static final String RECLAIM_EXHAUSTED_CODE = "OUTBOX_RETRY_EXHAUSTED";
    private static final String RECLAIM_EXHAUSTED_MESSAGE =
            "Lease expired after reaching maximum delivery attempts";

    @Qualifier("outboxMessageRepository")
    private final OutboxMessageRepository repository;

    @Qualifier("outboxMessageConverterImpl")
    private final OutboxMessageConverter converter;

    @Qualifier("outboxTechnicalContextExecutor")
    private final OutboxTechnicalContextExecutor technicalContext;

    @Qualifier("outboxLifecycleService")
    private final OutboxLifecycleService lifecycle;

    @Qualifier("outboxWorkerTransactionTemplate")
    private final TransactionTemplate workerTransaction;

    @Qualifier("outboxSchemaMetadataValidator")
    private final OutboxSchemaMetadataValidator metadataValidator;

    @Qualifier("egonColaMybatisPlusClock")
    private final Clock clock;

    @Qualifier("outboxMpStorageProperties")
    private final OutboxMpStorageProperties storageProperties;

    private final OutboxShardRouting shardRouting;
    private final int maxClaimContentionRetries;
    private final AtomicInteger nextShard = new AtomicInteger();

    public MybatisPlusOutboxStore(OutboxMessageRepository repository, OutboxMessageConverter converter,
                                  OutboxTechnicalContextExecutor technicalContext, OutboxLifecycleService lifecycle,
                                  TransactionTemplate workerTransaction, OutboxSchemaMetadataValidator metadataValidator,
                                  Clock clock, OutboxMpStorageProperties storageProperties) {
        this(repository, converter, technicalContext, lifecycle, workerTransaction, metadataValidator, clock,
                storageProperties, new OutboxShardRouting(), DEFAULT_MAX_CLAIM_CONTENTION_RETRIES);
    }

    public MybatisPlusOutboxStore(OutboxMessageRepository repository, OutboxMessageConverter converter,
                                  OutboxTechnicalContextExecutor technicalContext, OutboxLifecycleService lifecycle,
                                  TransactionTemplate workerTransaction, OutboxSchemaMetadataValidator metadataValidator,
                                  Clock clock, OutboxMpStorageProperties storageProperties, OutboxShardRouting shardRouting) {
        this(repository, converter, technicalContext, lifecycle, workerTransaction, metadataValidator, clock,
                storageProperties, shardRouting, DEFAULT_MAX_CLAIM_CONTENTION_RETRIES);
    }

    public MybatisPlusOutboxStore(OutboxMessageRepository repository, OutboxMessageConverter converter,
                                  OutboxTechnicalContextExecutor technicalContext, OutboxLifecycleService lifecycle,
                                  TransactionTemplate workerTransaction, OutboxSchemaMetadataValidator metadataValidator,
                                  Clock clock, OutboxMpStorageProperties storageProperties, OutboxShardRouting shardRouting,
                                  int maxClaimContentionRetries) {
        if (maxClaimContentionRetries < 1) {
            throw new IllegalArgumentException("OUTBOX_CLAIM_CONTENTION_RETRIES_INVALID");
        }
        this.repository = repository;
        this.converter = converter;
        this.technicalContext = technicalContext;
        this.lifecycle = lifecycle;
        this.workerTransaction = workerTransaction;
        this.metadataValidator = metadataValidator;
        this.clock = clock;
        this.storageProperties = storageProperties;
        this.shardRouting = shardRouting;
        this.maxClaimContentionRetries = maxClaimContentionRetries;
    }


    private volatile DispatchResources safeDispatch;
    private volatile TransactionalOutboxProperties dispatchOptions;

    public void configureConsumption(TransactionalOutboxProperties properties) {
        this.dispatchOptions = Objects.requireNonNull(properties);
    }

    public synchronized void configureSafeDispatch(TransactionalOutboxProperties properties, ObjectMapper objectMapper,
                                                   OutboxRetryPolicy retryPolicy, OutboxQueueMemberDAO members,
                                                   OutboxQueueLeaseDAO leases, OutboxQueueMemberConverter memberConverter,
                                                   OutboxQueueLeaseConverter leaseConverter) {
        if (safeDispatch != null) {
            throw new IllegalStateException("OUTBOX_DISPATCH_ALREADY_CONFIGURED");
        }
        var options = Objects.requireNonNull(properties);
        configureConsumption(options);
        TransactionTemplate transaction = new TransactionTemplate(workerTransaction.getTransactionManager(), workerTransaction);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(Math.max(1, Math.toIntExact(options.getCoordination().getClaimTimeout()
                .plusMillis(999).toSeconds())));
        safeDispatch = new DispatchResources(options, Objects.requireNonNull(objectMapper), Objects.requireNonNull(retryPolicy),
                Objects.requireNonNull(members), Objects.requireNonNull(leases), Objects.requireNonNull(memberConverter),
                Objects.requireNonNull(leaseConverter), transaction);
    }

    @Override
    public boolean supportsDispatchMode(DispatchMode mode) {
        return mode == DispatchMode.LEGACY || mode != null && safeDispatch != null;
    }

    @Override
    public List<Integer> dispatchSlots() {
        return shardRouting.slots();
    }

    @Override
    public OutboxQueueCoordinationBO coordinate(OutboxQueueCoordinationCommand command) {
        DispatchResources resources = safeResources();
        Objects.requireNonNull(command, "command");
        int slot = command.getShardSlot();
        shardRouting.requireSlot(slot);
        if (resources.options().getDispatch().getMode() != DispatchMode.COORDINATED
                || !command.isDraining() && !consumptionEnabled()) {
            throw new IllegalStateException("OUTBOX_QUEUE_COORDINATION_DISABLED");
        }
        validateCoordination(command);
        var options = resources.options().getCoordination();
        long ttl = command.isDraining() ? 0 : options.getMembershipTtl().toMillis();
        String json = writeCapabilities(resources, command.isDraining() ? Set.of() : command.getChannels());
        return safeTransaction(resources, slot, () -> {
            refreshMember(resources, slot, command.getInstanceId(), json, ttl);
            reviseLeases(resources, command, options.getLeaseDuration().toMillis());
            List<OutboxQueueMemberPO> active = technicalContext.executeMember(
                    () -> resources.members().active(slot, MAX_MEMBERS + 1));
            List<OutboxQueueLeasePO> leases = technicalContext.executeLease(
                    () -> resources.leases().active(slot, MAX_QUEUES + 1));
            if (active.size() > MAX_MEMBERS || leases.size() > MAX_QUEUES) {
                throw new OutboxStorageException("Outbox coordination snapshot exceeds its limit");
            }
            if (!command.isDraining() && !active.isEmpty()
                    && active.getFirst().getInstanceId().equals(command.getInstanceId())) {
                pruneMembership(resources, slot, options.getMemberRetention().toMillis());
            }
            var memberView = active.stream().map(row -> resources.memberConverter().toTarget(
                    row.setChannels(readCapabilities(resources, row.getCapabilitiesJson())))).toList();
            Map<String, OutboxQueueLease> leaseView = new LinkedHashMap<>();
            for (OutboxQueueLeasePO lease : leases) {
                leaseView.put(lease.getChannel(), resources.leaseConverter().toTarget(lease.setShardSlot(slot)));
            }
            return OutboxQueueCoordinationBO.builder().members(memberView).leases(Map.copyOf(leaseView))
                    .databaseTime(active.isEmpty() ? null : active.getFirst().getDatabaseTime()).build();
        });
    }

    private void validateCoordination(OutboxQueueCoordinationCommand command) {
        if (command.getInstanceId() == null || command.getInstanceId().isBlank()
                || command.getInstanceId().length() > 128 || command.getChannels() == null
                || command.getChannels().size() > 128 || command.getAcquire() == null
                || !command.getChannels().containsAll(command.getAcquire())
                || command.getRenew() == null || command.getRelease() == null
                || command.getRenew().size() > 128 || command.getRelease().size() > 128
                || command.isDraining() && (!command.getRenew().isEmpty() || !command.getAcquire().isEmpty())) {
            throw new IllegalArgumentException("OUTBOX_COORDINATION_COMMAND_INVALID");
        }
        int slot = command.getShardSlot();
        command.getChannels().forEach(channel -> new OutboxQueueKey(slot, channel));
        Set<String> intents = new LinkedHashSet<>(command.getAcquire());
        for (OutboxQueueLease lease : Stream.concat(command.getRenew().stream(), command.getRelease().stream()).toList()) {
            if (lease == null || lease.key().shardSlot() != slot || !command.getInstanceId().equals(lease.instanceId())
                    || !intents.add(lease.key().channel())) {
                throw new IllegalArgumentException("OUTBOX_COORDINATION_INTENT_INVALID");
            }
        }
        if (command.getRenew().stream().anyMatch(lease -> !command.getChannels().contains(lease.key().channel()))) {
            throw new IllegalArgumentException("OUTBOX_COORDINATION_CAPABILITY_REQUIRED");
        }
    }

    private void refreshMember(DispatchResources resources, int slot, String instance, String json, long ttl) {
        technicalContext.executeMember(() -> {
            if (resources.members().refresh(slot, instance, json, ttl) == 0
                    && resources.members().register(slot, nextId(), instance, json, ttl) == 0) {
                resources.members().refresh(slot, instance, json, ttl);
            }
            return null;
        });
    }

    private void reviseLeases(DispatchResources resources, OutboxQueueCoordinationCommand command, long leaseMillis) {
        int slot = command.getShardSlot();
        String instance = command.getInstanceId();
        technicalContext.executeLease(() -> {
            Map<String, Runnable> actions = new TreeMap<>();
            for (OutboxQueueLease release : command.getRelease()) {
                actions.put(release.key().channel(), () -> resources.leases().release(
                        slot, release.key().channel(), instance, release.epoch()));
            }
            for (OutboxQueueLease renew : command.getRenew()) {
                actions.put(renew.key().channel(), () -> resources.leases().renew(
                        slot, renew.key().channel(), instance, renew.epoch(), leaseMillis));
            }
            for (String channel : command.getAcquire()) {
                actions.put(channel, () -> {
                    if (resources.leases().acquireExpired(slot, channel, instance, leaseMillis) == 0) {
                        resources.leases().register(slot, nextId(), channel, instance, leaseMillis);
                    }
                });
            }
            actions.values().forEach(Runnable::run);
            return null;
        });
    }

    private void pruneMembership(DispatchResources resources, int slot, long retentionMillis) {
        technicalContext.executeMember(() -> {
            for (Long id : resources.members().staleIds(slot, retentionMillis, 16)) {
                resources.members().removeStale(slot, id, retentionMillis);
            }
            return null;
        });
    }

    private static String writeCapabilities(DispatchResources resources, Set<String> channels) {
        try {
            String json = resources.mapper().writeValueAsString(channels.stream().sorted().toList());
            if (json.getBytes(StandardCharsets.UTF_8).length > 32768) {
                throw new IllegalArgumentException("OUTBOX_COORDINATION_CAPABILITIES_TOO_LARGE");
            }
            return json;
        } catch (JsonProcessingException failure) {
            throw new OutboxStorageException("Failed to encode outbox capabilities", failure);
        }
    }

    private static Set<String> readCapabilities(DispatchResources resources, String json) {
        try {
            List<String> channels = resources.mapper().readValue(json, new TypeReference<List<String>>() { });
            if (channels == null || channels.size() > 128) {
                throw new OutboxStorageException("Stored outbox capabilities are invalid");
            }
            for (String channel : channels) {
                new OutboxQueueKey(0, channel);
            }
            Set<String> unique = Set.copyOf(channels);
            if (unique.size() != channels.size()) {
                throw new OutboxStorageException("Stored outbox capabilities contain duplicate channels");
            }
            return unique;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new OutboxStorageException("Failed to decode outbox capabilities", failure);
        }
    }

    @Override
    public OutboxClaimBatchBO claim(OutboxClaimCommand command) {
        DispatchResources resources = safeResources();
        Objects.requireNonNull(command, "command");
        OutboxQueueKey key = Objects.requireNonNull(command.getQueueKey(), "queueKey");
        shardRouting.requireSlot(key.shardSlot());
        DispatchMode mode = resources.options().getDispatch().getMode();
        if (mode == DispatchMode.LEGACY || !consumptionEnabled()
                || storageProperties.isMigrationMode()) {
            throw new IllegalStateException("OUTBOX_SAFE_CLAIM_DISABLED");
        }
        if (command.getLimit() != 1 || command.getDueQuota() < 0 || command.getReclaimQuota() < 0
                || (long) command.getDueQuota() + command.getReclaimQuota() > 1
                || command.getClaimToken() == null || command.getClaimToken().isBlank()
                || command.getClaimToken().length() > 128 || command.getLeaseDuration() == null
                || command.getLeaseDuration().toMillis() < 1
                || mode == DispatchMode.LOCAL && command.getQueueLease() != null
                || command.getQueueLease() != null && !key.equals(command.getQueueLease().key())) {
            throw new IllegalArgumentException("OUTBOX_SAFE_CLAIM_INVALID");
        }
        return safeTransaction(resources, key.shardSlot(), () -> {
            OutboxQueueLease lease = command.getQueueLease();
            if (mode == DispatchMode.COORDINATED && !lockQueue(resources, lease)) {
                return OutboxClaimBatchBO.builder().notOwner(true).build();
            }
            OutboxClaimBatchBO batch = technicalContext.execute(() -> claimCandidate(key, command));
            if (mode == DispatchMode.COORDINATED && !stillOwnsQueue(resources, lease)) {
                throw new OutboxStorageException("Queue lease expired before the outbox claim completed");
            }
            return batch;
        });
    }

    private boolean lockQueue(DispatchResources resources, OutboxQueueLease lease) {
        if (lease == null) {
            return false;
        }
        return technicalContext.executeLease(() -> {
            var selected = resources.leases().lockQueue(lease.key().shardSlot(), lease.key().channel());
            return selected != null && selected.getEpoch() == lease.epoch()
                    && lease.instanceId().equals(selected.getOwnerInstanceId())
                    && Boolean.TRUE.equals(resources.leases().current(
                            lease.key().shardSlot(), lease.key().channel(), lease.instanceId(), lease.epoch()));
        });
    }

    private boolean stillOwnsQueue(DispatchResources resources, OutboxQueueLease lease) {
        return technicalContext.executeLease(() -> Boolean.TRUE.equals(resources.leases().current(
                lease.key().shardSlot(), lease.key().channel(), lease.instanceId(), lease.epoch())));
    }

    private OutboxClaimBatchBO claimCandidate(OutboxQueueKey key, OutboxClaimCommand command) {
        boolean reclaimFirst = command.getReclaimQuota() > 0;
        List<OutboxMessagePO> rows = repository.safeCandidates(key.channel(), reclaimFirst, 1);
        if (rows.isEmpty()) {
            rows = repository.safeCandidates(key.channel(), !reclaimFirst, 1);
        }
        if (rows.isEmpty()) {
            return new OutboxClaimBatchBO();
        }
        OutboxMessagePO candidate = rows.getFirst();
        OutboxLifecycleSignalEnum signal = candidate.getStatus() == OutboxStatus.PROCESSING
                ? OutboxLifecycleSignalEnum.RECLAIM : OutboxLifecycleSignalEnum.CLAIM;
        OutboxStatus target = lifecycle.evaluateSafeClaim(candidate.getStatus().name(), signal,
                candidate.getAttemptCount(), candidate.getMaxAttempts());
        if (target == OutboxStatus.DEAD) {
            int changed = repository.safeExhausted(candidate, RECLAIM_EXHAUSTED_CODE, RECLAIM_EXHAUSTED_MESSAGE);
            return OutboxClaimBatchBO.builder().exhaustedCount(changed).build();
        }
        if (repository.safeClaim(candidate, command.getClaimToken(), command.getLeaseDuration().toMillis()) != 1) {
            return new OutboxClaimBatchBO();
        }
        OutboxMessagePO claimed = repository.safeRow(candidate.getId());
        if (claimed == null || !command.getClaimToken().equals(claimed.getLockedBy())) {
            throw new OutboxStorageException("Claimed outbox message could not be reloaded from its shard");
        }
        return OutboxClaimBatchBO.builder().records(List.of(toRecord(claimed))).databaseTime(claimed.getUpdatedAt()).build();
    }

    @Override
    public OutboxFinishStatusEnum finishDelivery(OutboxFinishCommand command) {
        DispatchResources resources = safeResources();
        Objects.requireNonNull(command, "command");
        shardRouting.requireSlot(command.getShardSlot());
        if (command.getId() <= 0 || command.getAttemptCount() < 1 || command.getMessageId() == null
                || command.getMessageId().isBlank() || command.getMessageId().length() > 64
                || command.getClaimToken() == null || command.getClaimToken().isBlank()
                || command.getClaimToken().length() > 128
                || command.getDeliveryResult() == null || command.getDeliveryResult().kind() == null) {
            throw new IllegalArgumentException("OUTBOX_SAFE_FINISH_INVALID");
        }
        try {
            return safeTransaction(resources, command.getShardSlot(), () ->
                    technicalContext.execute(() -> finishLocked(resources, command)));
        } catch (OutboxStorageException | TransactionException uncertain) {
            log.warn("Outbox result remains unconfirmed, messageId={}, slot={}, failureType={}",
                    command.getMessageId(), command.getShardSlot(), uncertain.getClass().getSimpleName());
            return OutboxFinishStatusEnum.INDETERMINATE;
        }
    }

    private OutboxFinishStatusEnum finishLocked(DispatchResources resources, OutboxFinishCommand command) {
        OutboxMessagePO row = repository.safeRow(command.getId());
        if (row == null || !command.getMessageId().equals(row.getMessageId())) {
            return OutboxFinishStatusEnum.LEASE_LOST;
        }
        if (row.getAttemptCount() != command.getAttemptCount()) {
            return OutboxFinishStatusEnum.LEASE_LOST;
        }
        if (row.getStatus() == OutboxStatus.SUCCEEDED || row.getStatus() == OutboxStatus.DEAD) {
            return OutboxFinishStatusEnum.ALREADY_TERMINAL;
        }
        if (row.getStatus() != OutboxStatus.PROCESSING
                || !command.getClaimToken().equals(row.getLockedBy())) {
            return OutboxFinishStatusEnum.LEASE_LOST;
        }
        DeliveryResult result = command.getDeliveryResult();
        OutboxLifecycleSignalEnum signal = switch (result.kind()) {
            case SUCCESS -> OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED;
            case RETRYABLE_FAILURE -> OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE;
            case PERMANENT_FAILURE -> OutboxLifecycleSignalEnum.DELIVERY_PERMANENT;
        };
        OutboxStatus target = lifecycle.evaluate(row.getStatus().name(), signal, row.getAttemptCount(), row.getMaxAttempts());
        String code = target == OutboxStatus.DEAD && signal == OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE
                ? RECLAIM_EXHAUSTED_CODE : result.code();
        int rows = writeFinish(row, target, code, result.message(), target == OutboxStatus.RETRY_WAIT
                ? resources.retryPolicy().nextDelay(row.getAttemptCount()).toMillis() : 0);
        return rows == 1 ? OutboxFinishStatusEnum.APPLIED : OutboxFinishStatusEnum.LEASE_LOST;
    }

    private int writeFinish(OutboxMessagePO row, OutboxStatus target, String code, String message, long delayMillis) {
        long id = row.getId();
        long version = row.getVersion();
        String owner = row.getLockedBy();
        return switch (target) {
            case SUCCEEDED -> repository.updateSucceeded(id, version, owner, target, clock.instant(), TECHNICAL_USER_ID);
            case RETRY_WAIT -> repository.updateRetry(id, version, owner, target, delayMillis,
                    sanitize(code, 64), sanitize(message, 2000), clock.instant(), TECHNICAL_USER_ID);
            case DEAD -> repository.updateDead(id, version, owner, target, sanitize(code, 64),
                    sanitize(message, 2000), clock.instant(), TECHNICAL_USER_ID);
            default -> throw new OutboxStorageException("Unexpected outbox result state");
        };
    }

    private DispatchResources safeResources() {
        if (safeDispatch == null) {
            throw new UnsupportedOperationException("OUTBOX_SAFE_DISPATCH_NOT_CONFIGURED");
        }
        return safeDispatch;
    }

    private boolean consumptionEnabled() {
        return dispatchOptions == null || dispatchOptions.getDispatch().isConsumptionEnabled();
    }

    private void requireLegacyClaim() {
        if (!consumptionEnabled() || dispatchOptions != null
                && dispatchOptions.getDispatch().getMode() != DispatchMode.LEGACY) {
            throw new IllegalStateException("OUTBOX_LEGACY_CLAIM_DISABLED");
        }
    }

    private static long nextId() {
        return SnowflakeIdGenerator.nextLongId();
    }

    private <T> T safeTransaction(DispatchResources resources, int slot, Supplier<T> operation) {
        long started = System.nanoTime();
        return runSafeTransaction(resources, slot, operation, started);
    }

    private <T> T runSafeTransaction(DispatchResources resources, int slot, Supplier<T> operation, long started) {
        long budget = resources.options().getCoordination().getClaimTimeout().toNanos();
        try {
            return resources.transaction().execute(status -> OutboxShardContext.withSlot(slot, () -> {
                T result = operation.get();
                if (System.nanoTime() - started >= budget) {
                    throw new OutboxStorageException("Outbox shard operation exceeded its transaction budget");
                }
                return result;
            }));
        } catch (DataAccessException failure) {
            throw storageFailure("Outbox shard transaction failed", failure);
        }
    }

    private record DispatchResources(TransactionalOutboxProperties options, ObjectMapper mapper,
                                     OutboxRetryPolicy retryPolicy, OutboxQueueMemberDAO members,
                                     OutboxQueueLeaseDAO leases, OutboxQueueMemberConverter memberConverter,
                                     OutboxQueueLeaseConverter leaseConverter, TransactionTemplate transaction) {
    }


    @Override
    public void selectShard(String group) {
        shardRouting.selectGroup(group);
    }

    @Override
    public String prepareGeneratedMessageId(String generated) {
        return shardRouting.generatedIdentity(generated);
    }

    @Override
    public OutboxReceipt enqueue(@NotNull @Valid NewOutboxRecord record) {
        Objects.requireNonNull(record, "record");
        if (storageProperties.isMigrationMode()) {
            throw new OutboxConfigurationException("OUTBOX_MIGRATION_MODE");
        }
        int shardSlot = shardRouting.currentSlot();
        shardRouting.validateIdentity(shardSlot, record.messageId(), record.idempotencyKey());
        try {
            return OutboxShardContext.withSlot(shardSlot, () -> technicalContext.execute(() -> {
                OutboxMessagePO message = converter.toInsertPO(record);
                message.setShardSlot(shardSlot);
                if (message.getNextAttemptAt() == null) {
                    message.setNextAttemptAt(clock.instant());
                }
                message.setId(SnowflakeIdGenerator.nextLongId());
                message.setStatus(lifecycle.evaluate(
                        OutboxLifecycleStateMachineFactory.NEW_STATE,
                        OutboxLifecycleSignalEnum.ENQUEUE, 0, record.maxAttempts()));
                message.setAttemptCount(0);
                if (repository.insertMessage(message) == 1) {
                    return new OutboxReceipt(record.messageId(), record.idempotencyKey(), true);
                }
                return resolveExisting(record);
            }));
        } catch (OutboxIdempotencyConflictException | OutboxStorageException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw storageFailure("Failed to enqueue outbox message", failure);
        }
    }

    @Override
    public List<OutboxRecord> claimDue(@Positive @Max(10_000) int limit, @NotNull String leaseOwner,
                                      @NotNull Duration leaseDuration) {
        return claimDue(null, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimDue(
            Collection<String> channels,
            @Positive @Max(10_000) int limit,
            @NotNull String leaseOwner,
            @NotNull Duration leaseDuration
    ) {
        requireLegacyClaim();
        List<String> channelFilter = normalizeChannels(channels);
        if (channelFilter != null && channelFilter.isEmpty()) {
            return List.of();
        }
        requireLease(leaseOwner, leaseDuration);
        List<OutboxRecord> result = new ArrayList<>();
        for (int slot : rotatedSlots()) {
            int remaining = limit - result.size();
            if (remaining == 0) { break; }
            int scanLimit = candidateScanLimit(remaining);
            List<OutboxMessagePO> candidates = inWorkerTransaction("Failed to select due outbox messages", slot,
                    () -> channelFilter == null
                            ? repository.selectDueCandidates(scanLimit)
                            : repository.selectDueCandidates(channelFilter, scanLimit));
            result.addAll(claim(candidates, remaining, leaseOwner, leaseDuration, slot));
        }
        return List.copyOf(result);
    }

    @Override
    public List<OutboxRecord> claimByMessageIds(
            Collection<String> messageIds,
            @Positive @Max(10_000) int limit,
            @NotNull String leaseOwner,
            @NotNull Duration leaseDuration
    ) {
        return claimByMessageIds(messageIds, null, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimByMessageIds(
            Collection<String> messageIds,
            Collection<String> channels,
            @Positive @Max(10_000) int limit,
            @NotNull String leaseOwner,
            @NotNull Duration leaseDuration
    ) {
        requireLegacyClaim();
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<String> channelFilter = normalizeChannels(channels);
        if (channelFilter != null && channelFilter.isEmpty()) {
            return List.of();
        }
        requireLease(leaseOwner, leaseDuration);
        List<OutboxRecord> result = new ArrayList<>();
        for (int slot : rotatedSlots()) {
            int remaining = limit - result.size();
            if (remaining == 0) { break; }
            int scanLimit = candidateScanLimit(remaining);
            List<OutboxMessagePO> candidates = inWorkerTransaction("Failed to select outbox messages", slot,
                    () -> channelFilter == null
                            ? repository.selectByMessageIdsCandidates(messageIds, scanLimit)
                            : repository.selectByMessageIdsCandidates(messageIds, channelFilter, scanLimit));
            result.addAll(claim(candidates, remaining, leaseOwner, leaseDuration, slot));
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeChannels(Collection<String> channels) {
        if (channels == null) {
            return null;
        }
        if (channels.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>(channels.size());
        for (String channel : channels) {
            if (channel == null || channel.isBlank() || channel.length() > 64) {
                throw new IllegalArgumentException("OUTBOX_CHANNELS_INVALID");
            }
            unique.add(channel.trim());
        }
        if (unique.size() > MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException("OUTBOX_BATCH_COLLECTION_TOO_LARGE");
        }
        return List.copyOf(unique);
    }

    @Override
    public boolean markSucceeded(@Positive long id, @NotNull String leaseOwner) {
        return markSucceeded(id, leaseOwner, legacySlot());
    }

    @Override
    public boolean markSucceeded(@Positive long id, @NotNull String leaseOwner, int shardSlot) {
        shardRouting.requireSlot(shardSlot);
        return inWorkerTransaction("Failed to complete outbox message", shardSlot, () -> {
            OutboxMessagePO current = repository.selectProcessingForUpdate(id, leaseOwner);
            if (current == null) {
                return false;
            }
            OutboxStatus target = lifecycle.evaluate(
                    current.getStatus().getMessage(), OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED,
                    current.getAttemptCount(), current.getMaxAttempts());
            return repository.updateSucceeded(id, current.getVersion(), leaseOwner, target,
                    clock.instant(), TECHNICAL_USER_ID) == 1;
        });
    }

    @Override
    public boolean markRetry(@Positive long id, @NotNull String leaseOwner, @NotNull Duration delay,
                             String errorCode, String errorMessage) {
        return markRetry(id, leaseOwner, delay, errorCode, errorMessage, legacySlot());
    }

    @Override
    public boolean markRetry(@Positive long id, @NotNull String leaseOwner, @NotNull Duration delay,
                             String errorCode, String errorMessage, int shardSlot) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("OUTBOX_RETRY_DELAY_INVALID");
        }
        shardRouting.requireSlot(shardSlot);
        return inWorkerTransaction("Failed to retry outbox message", shardSlot, () -> {
            OutboxMessagePO current = repository.selectProcessingForUpdate(id, leaseOwner);
            if (current == null) {
                return false;
            }
            OutboxStatus target = lifecycle.evaluate(
                    current.getStatus().getMessage(), safeDispatch == null ? OutboxLifecycleSignalEnum.SCHEDULE_RETRY
                            : OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE,
                    current.getAttemptCount(), current.getMaxAttempts());
            if (target == OutboxStatus.DEAD) {
                return writeFinish(current, target, RECLAIM_EXHAUSTED_CODE, errorMessage, 0) == 1;
            }
            return repository.updateRetry(id, current.getVersion(), leaseOwner, target, delay.toMillis(),
                    sanitize(errorCode, 64), sanitize(errorMessage, 2000), clock.instant(),
                    TECHNICAL_USER_ID) == 1;
        });
    }

    @Override
    public boolean markDead(@Positive long id, @NotNull String leaseOwner, String errorCode, String errorMessage) {
        return markDead(id, leaseOwner, errorCode, errorMessage, legacySlot());
    }

    @Override
    public boolean markDead(@Positive long id, @NotNull String leaseOwner, String errorCode, String errorMessage,
                            int shardSlot) {
        shardRouting.requireSlot(shardSlot);
        return inWorkerTransaction("Failed to dead-letter outbox message", shardSlot, () -> {
            OutboxMessagePO current = repository.selectProcessingForUpdate(id, leaseOwner);
            if (current == null) {
                return false;
            }
            OutboxStatus target = lifecycle.evaluate(
                    current.getStatus().getMessage(), OutboxLifecycleSignalEnum.DELIVERY_PERMANENT,
                    current.getAttemptCount(), current.getMaxAttempts());
            return repository.updateDead(id, current.getVersion(), leaseOwner, target,
                    sanitize(errorCode, 64), sanitize(errorMessage, 2000), clock.instant(),
                    TECHNICAL_USER_ID) == 1;
        });
    }

    @Override
    public int deleteSucceeded(@NotNull Duration retention, @Positive @Max(10_000) int limit) {
        if (retention == null || retention.isNegative()) {
            throw new IllegalArgumentException("OUTBOX_RETENTION_INVALID");
        }
        int total = 0;
        for (int slot : rotatedSlots()) {
            int remaining = limit - total;
            if (remaining == 0) { break; }
            List<OutboxMessagePO> candidates = inWorkerTransaction("Failed to select completed outbox messages", slot,
                    () -> repository.selectExpiredSucceededCandidates(retention.toMillis(), candidateScanLimit(remaining)));
            total += inWorkerTransactionSkippingLocked("Failed to clean up outbox messages", slot, candidates, 0,
                    (available, contention) -> {
                int deleted = 0;
                for (OutboxMessagePO candidate : available) {
                    if (deleted >= remaining) break;
                    acquireCandidate(candidate, contention);
                    deleted += repository.deleteSucceededByIdVersion(candidate.getId(), candidate.getVersion(), retention.toMillis());
                }
                return deleted;
            });
        }
        return total;
    }

    @Override
    public int recoverExhausted(@Positive @Max(10_000) int limit, @NotNull Duration cooldown) {
        if (cooldown == null || cooldown.isNegative() || cooldown.isZero() || cooldown.toMillis() <= 0) {
            throw new IllegalArgumentException("OUTBOX_REDRIVE_COOLDOWN_INVALID");
        }
        int total = 0;
        for (int slot : rotatedSlots()) {
            int remaining = limit - total;
            if (remaining <= 0) break;
            total += inWorkerTransaction("Failed to recover exhausted outbox messages", slot, () -> {
                int changed = 0;
                for (OutboxMessagePO candidate : repository.selectExhaustedCandidates(cooldown.toMillis(), candidateScanLimit(remaining))) {
                    if (changed >= remaining) break;
                    OutboxStatus target = lifecycle.evaluate(OutboxStatus.DEAD.getMessage(),
                            OutboxLifecycleSignalEnum.REDRIVE_EXHAUSTED, candidate.getAttemptCount(), candidate.getMaxAttempts());
                    if (target != OutboxStatus.RETRY_WAIT) throw new IllegalStateException("OUTBOX_REDRIVE_TARGET_INVALID");
                    changed += repository.redriveExhausted(candidate.getId(), candidate.getVersion(), cooldown.toMillis(),
                            clock.instant(), TECHNICAL_USER_ID);
                }
                return changed;
            });
        }
        return total;
    }

    @Override
    public long countBacklog() {
        long total = 0;
        for (int slot : shardRouting.slots()) {
            total = Math.addExact(total,
                    inWorkerTransaction("Failed to count outbox backlog", slot, repository::countBacklog));
        }
        return total;
    }

    @Override
    public void validateSchema() {
        metadataValidator.validate();
    }

    private List<OutboxRecord> claim(
            List<OutboxMessagePO> candidates,
            int requestedLimit,
            String leaseOwner,
            Duration leaseDuration,
            int shardSlot
    ) {
        return inWorkerTransactionSkippingLocked("Failed to claim outbox messages", shardSlot, candidates, List.of(),
                (available, contention) -> {
            List<OutboxRecord> claimed = new ArrayList<>(Math.min(requestedLimit, available.size()));
            int contentionMisses = 0;
            for (OutboxMessagePO candidate : available) {
                if (claimed.size() >= requestedLimit) break;
                acquireCandidate(candidate, contention);
                OutboxLifecycleSignalEnum signal = candidate.getStatus() == OutboxStatus.PROCESSING
                        ? OutboxLifecycleSignalEnum.RECLAIM : OutboxLifecycleSignalEnum.CLAIM;
                OutboxStatus target = lifecycle.evaluate(candidate.getStatus().getMessage(), signal,
                        candidate.getAttemptCount(), candidate.getMaxAttempts());
                if (signal == OutboxLifecycleSignalEnum.RECLAIM && target == OutboxStatus.DEAD) {
                    int deadUpdated = repository.updateReclaimDead(candidate.getId(), candidate.getVersion(),
                            RECLAIM_EXHAUSTED_CODE, RECLAIM_EXHAUSTED_MESSAGE, clock.instant(), TECHNICAL_USER_ID);
                    if (deadUpdated == 1) {
                        log.warn("Marked expired outbox message id={} messageId={} dead during reclaim after exhausting attempts ({}/{})",
                                candidate.getId(), candidate.getMessageId(),
                                candidate.getAttemptCount(), candidate.getMaxAttempts());
                    }
                    continue;
                }
                int changed = repository.updateClaim(candidate.getId(), candidate.getVersion(),
                        candidate.getStatus(), target, leaseOwner, leaseDuration.toMillis(), clock.instant(),
                        TECHNICAL_USER_ID);
                if (changed == 0) {
                    if (++contentionMisses >= maxClaimContentionRetries) {
                        log.debug("Stopping claim scan in shard {} after {} version-contention misses",
                                shardSlot, contentionMisses);
                        break;
                    }
                    continue;
                }
                OutboxMessagePO updated = repository.selectProcessingForUpdate(candidate.getId(), leaseOwner);
                if (updated == null) throw new OutboxStorageException("Claimed outbox message could not be reloaded");
                claimed.add(toRecord(updated));
            }
            return List.copyOf(claimed);
        });
    }

    private int candidateScanLimit(int requestedLimit) {
        return (int) Math.min(MAX_CANDIDATE_LIMIT, (long) requestedLimit * 4L);
    }

    private OutboxReceipt resolveExisting(NewOutboxRecord record) {
        List<OutboxMessagePO> existing = repository.selectExistingByIdentity(
                record.messageId(), record.idempotencyKey());
        if (existing.size() != 1
                || !existing.getFirst().getMessageFingerprint().equals(record.messageFingerprint())) {
            throw new OutboxIdempotencyConflictException(
                    "Outbox message identifier conflicts with an existing message");
        }
        OutboxMessagePO resolved = existing.getFirst();
        return new OutboxReceipt(resolved.getMessageId(), resolved.getIdempotencyKey(), false);
    }

    private List<Integer> rotatedSlots() {
        List<Integer> slots = new ArrayList<>(shardRouting.slots());
        java.util.Collections.rotate(slots, -Math.floorMod(nextShard.getAndIncrement(), slots.size()));
        return slots;
    }

    private int legacySlot() {
        if (shardRouting.slots().size() != 1) {
            throw new IllegalArgumentException("OUTBOX_SHARD_SLOT_REQUIRED");
        }
        return 0;
    }

    private <T> T inWorkerTransaction(String publicMessage, int shardSlot, Supplier<T> operation) {
        try {
            if (safeDispatch != null) {
                return safeTransaction(safeDispatch, shardSlot, () -> technicalContext.execute(operation));
            }
            return workerTransaction.execute(status -> OutboxShardContext.withSlot(
                    shardSlot, () -> technicalContext.execute(operation)));
        } catch (OutboxStorageException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw storageFailure(publicMessage, failure);
        }
    }

    /** A NOWAIT error aborts PostgreSQL's transaction, so retry the entire batch after excluding that row. */
    private <T> T inWorkerTransactionSkippingLocked(String publicMessage, int shardSlot,
            List<OutboxMessagePO> candidates, T empty, BiFunction<List<OutboxMessagePO>, LockContention, T> operation) {
        List<OutboxMessagePO> remaining = new ArrayList<>(candidates);
        int lockContentions = 0;
        while (!remaining.isEmpty()) {
            LockContention contention = new LockContention();
            try {
                return inWorkerTransaction(publicMessage, shardSlot, () -> operation.apply(remaining, contention));
            } catch (RuntimeException failure) {
                if (!contention.matches(failure)) throw failure;
                if (++lockContentions >= maxClaimContentionRetries) {
                    log.debug("Stopping claim scan in shard {} after {} lock-contention retries",
                            shardSlot, lockContentions);
                    return empty;
                }
                remaining.removeIf(candidate -> candidate.getId().equals(contention.id));
            }
        }
        return empty;
    }

    private void acquireCandidate(OutboxMessagePO candidate, LockContention contention) {
        try {
            if (repository.tryAcquireTransactionLock(candidate.getId())) return;
            OutboxStorageException blocked = new OutboxStorageException("Outbox row is already claimed");
            contention.record(candidate.getId(), blocked);
            throw blocked;
        } catch (RuntimeException failure) {
            if (isLockUnavailable(failure)) contention.record(candidate.getId(), failure);
            throw failure;
        }
    }

    private static final class LockContention {
        private Long id;
        private RuntimeException expected;

        private void record(Long id, RuntimeException failure) {
            this.id = id;
            this.expected = failure;
        }

        private boolean matches(RuntimeException failure) {
            return id != null && expected != null && (failure == expected || failure.getCause() == expected);
        }
    }

    private static boolean isLockUnavailable(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private OutboxRecord toRecord(OutboxMessagePO message) {
        try {
            return converter.toTarget(message);
        } catch (OutboxValidationException failure) {
            throw storageFailure("Failed to read outbox message headers", failure);
        }
    }

    private static void requireLease(String leaseOwner, Duration leaseDuration) {
        if (leaseOwner == null || leaseOwner.isBlank() || leaseDuration == null
                || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("OUTBOX_LEASE_INVALID");
        }
    }

    private static String sanitize(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maximumLength));
        value.codePoints().forEach(codePoint -> {
            if (Character.charCount(codePoint) <= maximumLength - sanitized.length()) {
                sanitized.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            }
        });
        return sanitized.toString();
    }

    private static OutboxStorageException storageFailure(String publicMessage, Exception cause) {
        return new OutboxStorageException(publicMessage, cause);
    }
}
