package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
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

import java.time.Clock;
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
                    current.getStatus().getMessage(), OutboxLifecycleSignalEnum.SCHEDULE_RETRY,
                    current.getAttemptCount(), current.getMaxAttempts());
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
