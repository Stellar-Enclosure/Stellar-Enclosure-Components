package top.egon.cola.component.outbox.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.common.exception.OutboxValidationException;
import top.egon.cola.component.outbox.persistence.converter.OutboxHeadersConverter;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.retry.OutboxRetryPolicy;
import top.egon.cola.component.outbox.serialization.SerializedOutboxPayload;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleSignalEnum;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleStateMachineFactory;
import top.egon.cola.component.outbox.transaction.OutboxTransactionGuard;
import top.egon.cola.component.outbox.validation.OutboxMessageValidator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** PostgreSQL implementation for a single ordinary DataSource and its own transaction boundary. */
@Slf4j
public final class JdbcOutboxStore implements OutboxStore {
    private static final int MAX_BATCH = 10_000;
    private static final String RECLAIM_EXHAUSTED_MESSAGE =
            "Lease expired after reaching maximum delivery attempts";
    private static final String COLUMNS = "id, message_id, idempotency_key, message_fingerprint, channel, "
            + "destination, payload, content_type, schema_version, headers_json, trace_id, status, attempt_count, "
            + "max_attempts, next_attempt_at, locked_by, locked_until, last_error_code, last_error_message, "
            + "created_at, updated_at, completed_at";
    private final JdbcTemplate jdbc;
    private final String schema;
    private final String table;
    private final TransactionTemplate workerTransaction;
    private final OutboxTransactionGuard transactionGuard;
    private final OutboxLifecycleService lifecycle;
    private final OutboxHeadersConverter headers;
    private final OutboxMessageValidator validator;
    private final ValidationUtils validationUtils;
    private final TransactionalOutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final OutboxRetryPolicy retryPolicy;
    private final String memberTable;
    private final String queueTable;
    private volatile boolean coordinationReady;
    private static final int MAX_MEMBERS = 1024;
    private static final int MEMBER_CLEANUP_LIMIT = 100;
    private static final long MEMBER_CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private final AtomicLong lastMemberCleanup = new AtomicLong(System.nanoTime() - MEMBER_CLEANUP_INTERVAL_NANOS);
    private final RowMapper<OutboxRecord> rowMapper = this::read;

    public JdbcOutboxStore(DataSource dataSource, String schema, TransactionTemplate workerTransaction,
                           OutboxTransactionGuard transactionGuard, OutboxLifecycleService lifecycle,
                           OutboxHeadersConverter headers, OutboxMessageValidator validator, ValidationUtils validationUtils) {
        this(dataSource, schema, workerTransaction, transactionGuard, lifecycle, headers, validator,
                validationUtils, new TransactionalOutboxProperties(), null, null);
    }

    public JdbcOutboxStore(DataSource dataSource, String schema, TransactionTemplate workerTransaction,
                          OutboxTransactionGuard transactionGuard, OutboxLifecycleService lifecycle,
                          OutboxHeadersConverter headers, OutboxMessageValidator validator,
                          ValidationUtils validationUtils, TransactionalOutboxProperties properties,
                          ObjectMapper objectMapper, OutboxRetryPolicy retryPolicy) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("OUTBOX_JDBC_SCHEMA_INVALID");
        }
        this.schema = schema;
        this.table = '"' + schema + "\".\"egon_cola_outbox_message\"";
        this.memberTable = '"' + schema + "\".\"egon_cola_outbox_queue_member\"";
        this.queueTable = '"' + schema + "\".\"egon_cola_outbox_queue_lease\"";
        this.workerTransaction = Objects.requireNonNull(workerTransaction, "workerTransaction");
        this.transactionGuard = Objects.requireNonNull(transactionGuard, "transactionGuard");
        if (!transactionGuard.usesDataSource(dataSource)
                || workerTransaction.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            throw new IllegalArgumentException("OUTBOX_JDBC_TRANSACTION_CONFIGURATION_INVALID");
        }
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.validationUtils = Objects.requireNonNull(validationUtils, "validationUtils");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        if (properties.getDispatch().getMode() != DispatchMode.LEGACY
                && (objectMapper == null || retryPolicy == null)) {
            throw new OutboxConfigurationException("OUTBOX_SAFE_STORE_DEPENDENCIES_REQUIRED");
        }
    }

    @Override
    public boolean supportsDispatchMode(DispatchMode mode) {
        return mode == DispatchMode.LEGACY || mode != null && objectMapper != null && retryPolicy != null;
    }

    @Override
    public OutboxQueueCoordinationBO coordinate(OutboxQueueCoordinationCommand command) {
        requireSafeMode();
        validationUtils.validate(command);
        if (properties.getDispatch().getMode() != DispatchMode.COORDINATED) {
            throw new OutboxConfigurationException("OUTBOX_QUEUE_COORDINATION_MODE_REQUIRED");
        }
        requireSlot(command.getShardSlot());
        requireOwner(command.getInstanceId());
        if (!command.isDraining()) requireConsumption();
        if (!command.getChannels().containsAll(command.getAcquire())
                || !command.isDraining() && command.getRenew().stream()
                .anyMatch(lease -> !command.getChannels().contains(lease.key().channel()))
                || command.getRenew().stream().anyMatch(lease -> !validQueueCommand(command, lease))
                || command.getRelease().stream().anyMatch(lease -> !validQueueCommand(command, lease))) {
            throw new IllegalArgumentException("OUTBOX_QUEUE_COORDINATION_COMMAND_INVALID");
        }
        String capabilities = serializeCapabilities(command.getChannels());
        return inSafeTransaction("OUTBOX_COORDINATION_TRANSACTION_INDETERMINATE", () -> {
            ensureCoordinationReady();
            TreeSet<String> queues = new TreeSet<>(command.getAcquire());
            command.getRenew().forEach(lease -> queues.add(lease.key().channel()));
            command.getRelease().forEach(lease -> queues.add(lease.key().channel()));
            // The same deterministic order is used by acquisition, release and renewal.
            for (String channel : queues) lockedQueue(channel, "FOR UPDATE");
            long ttl = command.isDraining() || command.getChannels().isEmpty() ? 0
                    : millis(properties.getCoordination().getMembershipTtl(), true, "OUTBOX_MEMBERSHIP_TTL_INVALID");
            jdbc.update("INSERT INTO " + memberTable + " (shard_slot, instance_id, capabilities_json, last_seen_at, expires_at) "
                            + "VALUES (0, ?, ?, clock_timestamp(), clock_timestamp()+(? * interval '1 millisecond')) "
                            + "ON CONFLICT (shard_slot, instance_id) DO UPDATE SET capabilities_json=EXCLUDED.capabilities_json, "
                            + "last_seen_at=EXCLUDED.last_seen_at, expires_at=EXCLUDED.expires_at",
                    command.getInstanceId(), capabilities, ttl);

            Map<String, OutboxQueueLease> owned = new LinkedHashMap<>();
            for (String channel : queues) {
                OutboxQueueLease release = findLease(command.getRelease(), channel);
                OutboxQueueLease renew = findLease(command.getRenew(), channel);
                if (release != null || command.isDraining() && renew != null) {
                    OutboxQueueLease proof = release == null ? renew : release;
                    jdbc.update("UPDATE " + queueTable + " SET expires_at=clock_timestamp() WHERE shard_slot=0 AND channel=? "
                                    + "AND owner_instance_id=? AND epoch=? AND expires_at > clock_timestamp()",
                            channel, command.getInstanceId(), proof.epoch());
                    continue;
                }
                if (command.isDraining()) continue;
                if (renew != null) {
                    jdbc.update("UPDATE " + queueTable + " SET expires_at=clock_timestamp()+(? * interval '1 millisecond') "
                                    + "WHERE shard_slot=0 AND channel=? AND owner_instance_id=? AND epoch=? "
                                    + "AND expires_at > clock_timestamp()",
                            queueLeaseMillis(), channel, command.getInstanceId(), renew.epoch());
                }
                if (command.getAcquire().contains(channel)) acquireQueue(channel, command.getInstanceId());
                OutboxQueueLease current = lockedQueue(channel, "FOR UPDATE");
                if (current != null && current.instanceId().equals(command.getInstanceId())
                        && current.expiresAt().isAfter(databaseTime())) owned.put(channel, current);
            }
            List<OutboxQueueCoordinationBO.Member> members = jdbc.query("SELECT instance_id, capabilities_json, expires_at "
                            + "FROM " + memberTable + " WHERE shard_slot=0 AND expires_at > clock_timestamp() "
                            + "ORDER BY instance_id LIMIT ?",
                    (rs, row) -> new OutboxQueueCoordinationBO.Member(rs.getString("instance_id"),
                            parseCapabilities(rs.getString("capabilities_json")), instant(rs, "expires_at")), MAX_MEMBERS + 1);
            if (members.size() > MAX_MEMBERS) {
                throw new OutboxStorageException("OUTBOX_MEMBER_SNAPSHOT_LIMIT_EXCEEDED");
            }
            long cleanupNow = System.nanoTime();
            long previousCleanup = lastMemberCleanup.get();
            if (!members.isEmpty() && members.getFirst().instanceId().equals(command.getInstanceId())
                    && cleanupNow - previousCleanup >= MEMBER_CLEANUP_INTERVAL_NANOS
                    && lastMemberCleanup.compareAndSet(previousCleanup, cleanupNow)) {
                long retention = millis(properties.getCoordination().getMemberRetention(), true, "OUTBOX_MEMBER_RETENTION_INVALID");
                jdbc.update("DELETE FROM " + memberTable + " WHERE shard_slot=0 AND expires_at <= "
                                + "clock_timestamp()-(? * interval '1 millisecond') AND id IN (SELECT id FROM " + memberTable
                                + " WHERE shard_slot=0 AND expires_at <= clock_timestamp()-(? * interval '1 millisecond') "
                                + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)", retention, retention, MEMBER_CLEANUP_LIMIT);
            }
            return OutboxQueueCoordinationBO.builder().databaseTime(databaseTime())
                    .members(List.copyOf(members)).leases(Map.copyOf(owned)).build();
        });
    }

    @Override
    public OutboxClaimBatchBO claim(OutboxClaimCommand command) {
        requireSafeMode();
        requireConsumption();
        validationUtils.validate(command);
        requireSlot(command.getQueueKey().shardSlot());
        requireBatch(command.getLimit());
        if (command.getLimit() > properties.getDispatch().getMaxClaimBatch()
                || command.getLimit() > properties.getPolling().getBatchSize()
                || (long) command.getDueQuota() + command.getReclaimQuota() > command.getLimit()) {
            throw new IllegalArgumentException("OUTBOX_SAFE_CLAIM_LIMIT_INVALID");
        }
        long leaseMillis = millis(command.getLeaseDuration(), true, "OUTBOX_LEASE_INVALID");
        if (!command.getLeaseDuration().equals(properties.getDelivery().getLeaseDuration())) {
            throw new IllegalArgumentException("OUTBOX_MESSAGE_LEASE_CONFIGURATION_MISMATCH");
        }
        if (properties.getDispatch().getMode() == DispatchMode.COORDINATED
                && (command.getQueueLease() == null
                || !command.getQueueKey().equals(command.getQueueLease().key()))) {
            throw new IllegalArgumentException("OUTBOX_QUEUE_LEASE_REQUIRED");
        }
        return inSafeTransaction("OUTBOX_CLAIM_TRANSACTION_INDETERMINATE", () -> {
            OutboxQueueLease authority = null;
            if (properties.getDispatch().getMode() == DispatchMode.COORDINATED) {
                ensureCoordinationReady();
                authority = lockedQueue(command.getQueueKey().channel(), "FOR SHARE");
                if (!matchesLease(authority, command.getQueueLease(), databaseTime())) {
                    return OutboxClaimBatchBO.builder().databaseTime(databaseTime()).notOwner(true).build();
                }
            }
            List<OutboxRecord> candidates = new ArrayList<>();
            int due = command.getDueQuota();
            int reclaim = command.getReclaimQuota();
            if (due + reclaim == 0) due = command.getLimit();
            selectSafeCandidates(command, false, due, candidates);
            selectSafeCandidates(command, true, reclaim, candidates);
            selectSafeCandidates(command, false, command.getLimit() - candidates.size(), candidates);
            selectSafeCandidates(command, true, command.getLimit() - candidates.size(), candidates);
            List<Long> active = new ArrayList<>();
            List<Long> exhausted = new ArrayList<>();
            for (OutboxRecord candidate : candidates) {
                OutboxLifecycleSignalEnum signal = candidate.status() == OutboxStatus.PROCESSING
                        ? OutboxLifecycleSignalEnum.RECLAIM : OutboxLifecycleSignalEnum.CLAIM;
                OutboxStatus target = lifecycle.evaluateSafeClaim(candidate.status().name(), signal,
                        candidate.attemptCount(), candidate.maxAttempts());
                if (target == OutboxStatus.PROCESSING) active.add(candidate.id());
                else if (target == OutboxStatus.DEAD) exhausted.add(candidate.id());
                else throw new OutboxStorageException("OUTBOX_SAFE_CLAIM_TARGET_INVALID");
            }
            int exhaustedCount = terminalizeExhausted(exhausted, command.getQueueKey().channel());
            List<OutboxRecord> claimed = updateSafeClaims(active, command.getQueueKey().channel(),
                    command.getClaimToken(), leaseMillis);
            Instant now = databaseTime();
            if (authority != null && !authority.expiresAt().isAfter(now)) {
                throw new OutboxStorageException("OUTBOX_QUEUE_LEASE_EXPIRED_DURING_CLAIM");
            }
            return OutboxClaimBatchBO.builder().databaseTime(now).records(List.copyOf(claimed))
                    .exhaustedCount(exhaustedCount).build();
        });
    }

    @Override
    public OutboxFinishStatusEnum finishDelivery(OutboxFinishCommand command) {
        requireSafeDependencies();
        validationUtils.validate(command);
        requireSlot(command.getShardSlot());
        if (command.getDeliveryResult().kind() == null) {
            throw new IllegalArgumentException("OUTBOX_DELIVERY_RESULT_INVALID");
        }
        try {
            return safeTransaction().execute(status -> {
                transactionGuard.requireSelectedTransaction();
                configureStatementTimeout();
                List<OutboxRecord> found = jdbc.query("SELECT " + COLUMNS + " FROM " + table + " WHERE id=? FOR UPDATE",
                        rowMapper, command.getId());
                if (found.isEmpty()) return OutboxFinishStatusEnum.LEASE_LOST;
                OutboxRecord current = found.getFirst();
                if (!current.messageId().equals(command.getMessageId())
                        || current.attemptCount() != command.getAttemptCount()) return OutboxFinishStatusEnum.LEASE_LOST;
                if (current.status() == OutboxStatus.SUCCEEDED || current.status() == OutboxStatus.DEAD) {
                    return OutboxFinishStatusEnum.ALREADY_TERMINAL;
                }
                Instant now = databaseTime();
                if (current.status() != OutboxStatus.PROCESSING
                        || !command.getClaimToken().equals(current.lockedBy())
                        || current.lockedUntil() == null || !current.lockedUntil().isAfter(now)) {
                    return OutboxFinishStatusEnum.LEASE_LOST;
                }
                OutboxLifecycleSignalEnum signal = switch (command.getDeliveryResult().kind()) {
                    case SUCCESS -> OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED;
                    case RETRYABLE_FAILURE -> OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE;
                    case PERMANENT_FAILURE -> OutboxLifecycleSignalEnum.DELIVERY_PERMANENT;
                };
                OutboxStatus target = lifecycle.evaluate(current.status().name(), signal,
                        current.attemptCount(), current.maxAttempts());
                boolean retry = target == OutboxStatus.RETRY_WAIT;
                boolean succeeded = target == OutboxStatus.SUCCEEDED;
                long delay = retry ? millis(retryPolicy.nextDelay(current.attemptCount()), false,
                        "OUTBOX_RETRY_DELAY_INVALID") : 0;
                String code = command.getDeliveryResult().code();
                String message = command.getDeliveryResult().message();
                if (target == OutboxStatus.DEAD && signal == OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE) {
                    code = "OUTBOX_RETRY_EXHAUSTED";
                }
                int changed = jdbc.update("UPDATE " + table + " SET status=?, locked_by=NULL, locked_until=NULL, "
                                + "next_attempt_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 millisecond') ELSE next_attempt_at END, "
                                + "last_error_code=?, last_error_message=?, completed_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END, "
                                + "updated_at=clock_timestamp() WHERE id=? AND message_id=? AND attempt_count=? "
                                + "AND status='PROCESSING' AND locked_by=? AND locked_until > clock_timestamp()",
                        target.name(), retry, delay, succeeded ? null : sanitize(code, 64),
                        succeeded ? null : sanitize(message, 2000), retry, current.id(), current.messageId(),
                        current.attemptCount(), command.getClaimToken());
                return changed == 1 ? OutboxFinishStatusEnum.APPLIED : OutboxFinishStatusEnum.LEASE_LOST;
            });
        } catch (DataAccessException | TransactionException failure) {
            log.warn("Outbox result acknowledgement could not be confirmed for message id={}", command.getId());
            return OutboxFinishStatusEnum.INDETERMINATE;
        }
    }

    @Override
    public OutboxReceipt enqueue(NewOutboxRecord record) {
        try {
            transactionGuard.requireSelectedTransaction();
            validate(record);
            OutboxStatus status = lifecycle.evaluate(OutboxLifecycleStateMachineFactory.NEW_STATE,
                    OutboxLifecycleSignalEnum.ENQUEUE, 0, record.maxAttempts());
            List<Long> inserted = jdbc.query("INSERT INTO " + table + " (message_id, idempotency_key, "
                            + "message_fingerprint, channel, destination, payload, content_type, schema_version, "
                            + "headers_json, trace_id, status, attempt_count, max_attempts, next_attempt_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, COALESCE(?, clock_timestamp()), "
                            + "clock_timestamp(), clock_timestamp()) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getLong(1), record.messageId(), record.idempotencyKey(), record.messageFingerprint(),
                    record.channel(), record.destination(), record.payload(), record.contentType(), record.schemaVersion(),
                    record.headersJson(), record.traceId(), status.name(), record.maxAttempts(), timestamp(record.availableAt()));
            if (!inserted.isEmpty()) return new OutboxReceipt(record.messageId(), record.idempotencyKey(), true);
            List<OutboxRecord> existing = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE message_id = ? OR idempotency_key = ?", rowMapper,
                    record.messageId(), record.idempotencyKey());
            if (existing.size() != 1 || !existing.getFirst().messageFingerprint().equals(record.messageFingerprint())) {
                throw new OutboxIdempotencyConflictException("Outbox message identifier conflicts with an existing message");
            }
            OutboxRecord found = existing.getFirst();
            return new OutboxReceipt(found.messageId(), found.idempotencyKey(), false);
        } catch (DataAccessException failure) {
            transactionGuard.markRollbackOnly();
            throw new OutboxStorageException("Failed to enqueue outbox message", failure);
        } catch (RuntimeException | Error failure) {
            transactionGuard.markRollbackOnly();
            throw failure;
        }
    }

    @Override
    public List<OutboxRecord> claimDue(int limit, String leaseOwner, Duration leaseDuration) {
        requireLegacyClaim();
        return claim(null, null, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimDue(
            Collection<String> channels,
            int limit,
            String leaseOwner,
            Duration leaseDuration
    ) {
        requireLegacyClaim();
        List<String> channelFilter = normalizeChannels(channels);
        if (channelFilter != null && channelFilter.isEmpty()) {
            return List.of();
        }
        return claim(null, channelFilter, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimByMessageIds(Collection<String> messageIds, int limit,
                                                 String leaseOwner, Duration leaseDuration) {
        return claimByMessageIds(messageIds, null, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimByMessageIds(
            Collection<String> messageIds,
            Collection<String> channels,
            int limit,
            String leaseOwner,
            Duration leaseDuration
    ) {
        requireLegacyClaim();
        if (messageIds == null || messageIds.isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>(messageIds);
        if (unique.size() > MAX_BATCH || unique.stream().anyMatch(id -> invalid(id, 64))) {
            throw new IllegalArgumentException("OUTBOX_MESSAGE_IDS_INVALID");
        }
        List<String> channelFilter = normalizeChannels(channels);
        if (channelFilter != null && channelFilter.isEmpty()) {
            return List.of();
        }
        return claim(List.copyOf(unique), channelFilter, limit, leaseOwner, leaseDuration);
    }

    private List<String> normalizeChannels(Collection<String> channels) {
        if (channels == null) {
            return null;
        }
        if (channels.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>(channels.size());
        for (String channel : channels) {
            if (invalid(channel, 64)) {
                throw new IllegalArgumentException("OUTBOX_CHANNELS_INVALID");
            }
            unique.add(channel.trim());
        }
        if (unique.size() > MAX_BATCH) {
            throw new IllegalArgumentException("OUTBOX_CHANNELS_INVALID");
        }
        return List.copyOf(unique);
    }

    private List<OutboxRecord> claim(List<String> ids, List<String> channels, int limit, String owner, Duration lease) {
        requireBatch(limit);
        requireOwner(owner);
        long leaseMillis = millis(lease, true, "OUTBOX_LEASE_INVALID");
        return inWorkerTransaction("Failed to claim outbox messages", () -> {
            String idFilter = ids == null ? "" : " AND message_id IN ("
                    + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
            String channelFilter = channels == null ? "" : " AND channel IN ("
                    + String.join(",", java.util.Collections.nCopies(channels.size(), "?")) + ")";
            List<Object> parameters = new ArrayList<>();
            if (ids != null) parameters.addAll(ids);
            if (channels != null) parameters.addAll(channels);
            parameters.add(limit);
            List<OutboxRecord> candidates = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp())"
                            + " OR (status = 'PROCESSING' AND locked_until < clock_timestamp()))"
                            + idFilter + channelFilter
                            + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    rowMapper, parameters.toArray());
            List<OutboxRecord> claimed = new ArrayList<>(candidates.size());
            for (OutboxRecord candidate : candidates) {
                OutboxLifecycleSignalEnum signal = candidate.status() == OutboxStatus.PROCESSING
                        ? OutboxLifecycleSignalEnum.RECLAIM : OutboxLifecycleSignalEnum.CLAIM;
                OutboxStatus target = lifecycle.evaluate(candidate.status().name(), signal,
                        candidate.attemptCount(), candidate.maxAttempts());
                if (signal == OutboxLifecycleSignalEnum.RECLAIM && target == OutboxStatus.DEAD) {
                    int deadUpdated = jdbc.update("UPDATE " + table + " SET status='DEAD', locked_by=NULL, "
                                    + "locked_until=NULL, last_error_code='OUTBOX_RETRY_EXHAUSTED', "
                                    + "last_error_message=?, completed_at=clock_timestamp(), "
                                    + "updated_at=clock_timestamp() "
                                    + "WHERE id=? AND status='PROCESSING' AND locked_until < clock_timestamp()",
                            RECLAIM_EXHAUSTED_MESSAGE, candidate.id());
                    if (deadUpdated == 1) {
                        log.warn("Marked expired outbox message id={} messageId={} dead during reclaim after exhausting attempts ({}/{})",
                                candidate.id(), candidate.messageId(), candidate.attemptCount(), candidate.maxAttempts());
                    }
                    continue;
                }
                int changed = jdbc.update("UPDATE " + table + " SET status=?, attempt_count=attempt_count+1, "
                                + "locked_by=?, locked_until=clock_timestamp()+(? * interval '1 millisecond'), "
                                + "updated_at=clock_timestamp() WHERE id=? AND status=? AND "
                                + "((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp()) "
                                + "OR (status='PROCESSING' AND locked_until < clock_timestamp()))",
                        target.name(), owner, leaseMillis, candidate.id(), candidate.status().name());
                if (changed == 1) {
                    List<OutboxRecord> updated = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                                    + " WHERE id=? AND status='PROCESSING' AND locked_by=? AND locked_until > clock_timestamp()",
                            rowMapper, candidate.id(), owner);
                    if (updated.size() != 1) throw new OutboxStorageException("Claimed outbox message could not be reloaded");
                    claimed.add(updated.getFirst());
                }
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public boolean markSucceeded(long id, String owner) {
        return markSucceeded(id, owner, 0);
    }

    @Override
    public boolean markSucceeded(long id, String owner, int slot) {
        return finish(id, owner, slot, OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, 0, null, null);
    }

    @Override
    public boolean markRetry(long id, String owner, Duration delay, String errorCode, String errorMessage) {
        return markRetry(id, owner, delay, errorCode, errorMessage, 0);
    }

    @Override
    public boolean markRetry(long id, String owner, Duration delay, String errorCode, String errorMessage, int slot) {
        return finish(id, owner, slot, OutboxLifecycleSignalEnum.SCHEDULE_RETRY,
                millis(delay, false, "OUTBOX_RETRY_DELAY_INVALID"), errorCode, errorMessage);
    }

    @Override
    public boolean markDead(long id, String owner, String errorCode, String errorMessage) {
        return markDead(id, owner, errorCode, errorMessage, 0);
    }

    @Override
    public boolean markDead(long id, String owner, String errorCode, String errorMessage, int slot) {
        return finish(id, owner, slot, OutboxLifecycleSignalEnum.DELIVERY_PERMANENT, 0, errorCode, errorMessage);
    }

    private boolean finish(long id, String owner, int slot, OutboxLifecycleSignalEnum signal,
                           long delayMillis, String code, String message) {
        if (id <= 0 || slot != 0) throw new IllegalArgumentException("OUTBOX_JDBC_ID_OR_SLOT_INVALID");
        requireOwner(owner);
        boolean safe = properties.getDispatch().getMode() != DispatchMode.LEGACY;
        if (safe) requireSafeMode();
        Supplier<Boolean> action = () -> {
            List<OutboxRecord> found = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE id=? AND status='PROCESSING' AND locked_by=? "
                            + "AND locked_until > clock_timestamp() FOR UPDATE", rowMapper, id, owner);
            if (found.isEmpty()) return false;
            OutboxRecord current = found.getFirst();
            OutboxLifecycleSignalEnum effectiveSignal = signal == OutboxLifecycleSignalEnum.SCHEDULE_RETRY
                    && properties.getDispatch().getMode() != DispatchMode.LEGACY
                    ? OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE : signal;
            OutboxStatus target = lifecycle.evaluate(current.status().name(), effectiveSignal,
                    current.attemptCount(), current.maxAttempts());
            boolean retry = target == OutboxStatus.RETRY_WAIT;
            boolean succeeded = target == OutboxStatus.SUCCEEDED;
            String effectiveCode = target == OutboxStatus.DEAD
                    && effectiveSignal == OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE
                    ? "OUTBOX_RETRY_EXHAUSTED" : code;
            return jdbc.update("UPDATE " + table + " SET status=?, locked_by=NULL, locked_until=NULL, "
                            + "next_attempt_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 millisecond') ELSE next_attempt_at END, "
                            + "last_error_code=?, last_error_message=?, completed_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END, "
                            + "updated_at=clock_timestamp() WHERE id=? AND status='PROCESSING' AND locked_by=? "
                            + "AND locked_until > clock_timestamp()",
                    target.name(), retry, delayMillis, succeeded ? null : sanitize(effectiveCode, 64),
                    succeeded ? null : sanitize(message, 2000), retry, id, owner) == 1;
        };
        return safe ? inSafeTransaction("OUTBOX_FINISH_TRANSACTION_INDETERMINATE", action)
                : inWorkerTransaction("Failed to update outbox message", action);
    }

    @Override
    public int deleteSucceeded(Duration retention, int limit) {
        requireBatch(limit);
        long retentionMillis = millis(retention, false, "OUTBOX_RETENTION_INVALID");
        return inWorkerTransaction("Failed to clean up outbox messages", () -> jdbc.update("WITH candidates AS ("
                        + "SELECT id FROM " + table + " WHERE status='SUCCEEDED' "
                        + "AND completed_at < clock_timestamp()-(? * interval '1 millisecond') "
                        + "ORDER BY completed_at,id LIMIT ? FOR UPDATE SKIP LOCKED) DELETE FROM " + table
                        + " WHERE id IN (SELECT id FROM candidates)", retentionMillis, limit));
    }

    @Override
    public int recoverExhausted(int limit, Duration cooldown) {
        requireLegacyClaim();
        requireBatch(limit);
        long cooldownMillis = millis(cooldown, true, "OUTBOX_REDRIVE_COOLDOWN_INVALID");
        return inWorkerTransaction("Failed to recover exhausted outbox messages", () -> {
            List<OutboxRecord> candidates = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE status='DEAD' AND last_error_code='OUTBOX_RETRY_EXHAUSTED' "
                            + "AND attempt_count >= max_attempts AND completed_at <= clock_timestamp()-(? * interval '1 millisecond') "
                            + "ORDER BY completed_at,id LIMIT ? FOR UPDATE SKIP LOCKED", rowMapper, cooldownMillis, limit);
            int changed = 0;
            for (OutboxRecord current : candidates) {
                OutboxStatus target = lifecycle.evaluate(current.status().name(), OutboxLifecycleSignalEnum.REDRIVE_EXHAUSTED,
                        current.attemptCount(), current.maxAttempts());
                changed += jdbc.update("UPDATE " + table + " SET status=?, attempt_count=0, "
                                + "next_attempt_at=clock_timestamp(), completed_at=NULL, updated_at=clock_timestamp() "
                                + "WHERE id=? AND status='DEAD' AND last_error_code='OUTBOX_RETRY_EXHAUSTED' "
                                + "AND attempt_count >= max_attempts AND completed_at <= clock_timestamp()-(? * interval '1 millisecond')",
                        target.name(), current.id(), cooldownMillis);
            }
            return changed;
        });
    }

    @Override
    public long countBacklog() {
        return inWorkerTransaction("Failed to count outbox backlog", () -> jdbc.queryForObject("SELECT count(*) FROM "
                + table + " WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT')", Long.class));
    }

    @Override
    public void validateSchema() {
        JdbcOutboxSchemaValidator schemaValidator = new JdbcOutboxSchemaValidator(jdbc, schema, validationUtils);
        schemaValidator.validate();
        if (properties.getDispatch().getMode() == DispatchMode.COORDINATED) {
            schemaValidator.validateCoordination();
            coordinationReady = true;
        }
    }

    private void ensureCoordinationReady() {
        if (coordinationReady) return;
        synchronized (this) {
            if (!coordinationReady) {
                new JdbcOutboxSchemaValidator(jdbc, schema, validationUtils).validateCoordination();
                coordinationReady = true;
            }
        }
    }

    private boolean validQueueCommand(OutboxQueueCoordinationCommand command, OutboxQueueLease lease) {
        return lease.key().shardSlot() == command.getShardSlot()
                && lease.instanceId().equals(command.getInstanceId());
    }

    private String serializeCapabilities(Set<String> channels) {
        try {
            String json = objectMapper.writeValueAsString(new TreeSet<>(channels));
            if (json.getBytes(StandardCharsets.UTF_8).length > 32768) {
                throw new IllegalArgumentException("OUTBOX_MEMBER_CAPABILITIES_INVALID");
            }
            return json;
        } catch (JsonProcessingException failure) {
            throw new OutboxValidationException("OUTBOX_MEMBER_CAPABILITIES_INVALID", failure);
        }
    }

    private Set<String> parseCapabilities(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 32768) {
            throw new OutboxStorageException("OUTBOX_STORED_MEMBER_CAPABILITIES_INVALID");
        }
        try {
            JsonNode values = objectMapper.readTree(json);
            if (!values.isArray() || values.size() > 128) {
                throw new OutboxStorageException("OUTBOX_STORED_MEMBER_CAPABILITIES_INVALID");
            }
            Set<String> channels = new LinkedHashSet<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || invalid(value.textValue(), 64)
                        || !value.textValue().equals(value.textValue().strip()) || !channels.add(value.textValue())) {
                    throw new OutboxStorageException("OUTBOX_STORED_MEMBER_CAPABILITIES_INVALID");
                }
            }
            return Set.copyOf(channels);
        } catch (JsonProcessingException failure) {
            throw new OutboxStorageException("OUTBOX_STORED_MEMBER_CAPABILITIES_INVALID", failure);
        }
    }

    private void acquireQueue(String channel, String instance) {
        jdbc.update("INSERT INTO " + queueTable + " (shard_slot, channel, owner_instance_id, epoch, expires_at) "
                        + "VALUES (0, ?, ?, 1, clock_timestamp()+(? * interval '1 millisecond')) "
                        + "ON CONFLICT (shard_slot, channel) DO NOTHING", channel, instance, queueLeaseMillis());
        OutboxQueueLease current = lockedQueue(channel, "FOR UPDATE");
        Instant now = databaseTime();
        if (current.expiresAt().isAfter(now)) return;
        if (current.epoch() == Long.MAX_VALUE) {
            log.error("Outbox queue epoch exhausted for shard 0 channel={}", channel);
            throw new OutboxStorageException("OUTBOX_QUEUE_EPOCH_EXHAUSTED");
        }
        jdbc.update("UPDATE " + queueTable + " SET owner_instance_id=?, epoch=epoch+1, "
                        + "expires_at=clock_timestamp()+(? * interval '1 millisecond') "
                        + "WHERE shard_slot=0 AND channel=? AND expires_at <= clock_timestamp() AND epoch < ?",
                instance, queueLeaseMillis(), channel, Long.MAX_VALUE);
    }

    private OutboxQueueLease lockedQueue(String channel, String lock) {
        List<OutboxQueueLease> leases = jdbc.query("SELECT channel, owner_instance_id, epoch, expires_at FROM "
                        + queueTable + " WHERE shard_slot=0 AND channel=? " + lock,
                (rs, row) -> new OutboxQueueLease(new OutboxQueueKey(0, rs.getString("channel")),
                        rs.getString("owner_instance_id"), rs.getLong("epoch"), instant(rs, "expires_at")), channel);
        return leases.isEmpty() ? null : leases.getFirst();
    }

    private static OutboxQueueLease findLease(List<OutboxQueueLease> leases, String channel) {
        return leases.stream().filter(lease -> lease.key().channel().equals(channel)).findFirst().orElse(null);
    }

    private static boolean matchesLease(OutboxQueueLease current, OutboxQueueLease supplied, Instant now) {
        return current != null && current.instanceId().equals(supplied.instanceId())
                && current.epoch() == supplied.epoch() && current.expiresAt().isAfter(now);
    }

    private long queueLeaseMillis() {
        return millis(properties.getCoordination().getLeaseDuration(), true, "OUTBOX_QUEUE_LEASE_DURATION_INVALID");
    }

    private void selectSafeCandidates(OutboxClaimCommand command, boolean reclaim, int limit, List<OutboxRecord> found) {
        if (limit <= 0) return;
        String predicate = reclaim ? "status='PROCESSING' AND locked_until <= clock_timestamp()"
                : "status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp()";
        List<Object> arguments = new ArrayList<>();
        arguments.add(command.getQueueKey().channel());
        String exclude = "";
        if (!found.isEmpty()) {
            exclude = " AND id NOT IN (" + placeholders(found.size()) + ")";
            found.forEach(record -> arguments.add(record.id()));
        }
        arguments.add(limit);
        found.addAll(jdbc.query("SELECT " + COLUMNS + " FROM " + table + " WHERE channel=? AND " + predicate
                        + exclude + " ORDER BY " + (reclaim ? "locked_until" : "next_attempt_at")
                        + ", id LIMIT ? FOR UPDATE SKIP LOCKED", rowMapper, arguments.toArray()));
    }

    private int terminalizeExhausted(List<Long> ids, String channel) {
        if (ids.isEmpty()) return 0;
        List<Object> arguments = new ArrayList<>();
        arguments.add(RECLAIM_EXHAUSTED_MESSAGE);
        arguments.add(channel);
        arguments.addAll(ids);
        List<Long> changed = jdbc.query("UPDATE " + table + " SET status='DEAD', locked_by=NULL, locked_until=NULL, "
                        + "last_error_code='OUTBOX_RETRY_EXHAUSTED', last_error_message=?, "
                        + "completed_at=clock_timestamp(), updated_at=clock_timestamp() WHERE channel=? "
                        + "AND id IN (" + placeholders(ids.size()) + ") AND attempt_count >= max_attempts "
                        + "AND ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp()) "
                        + "OR (status='PROCESSING' AND locked_until <= clock_timestamp())) RETURNING id",
                (rs, row) -> rs.getLong(1), arguments.toArray());
        if (changed.size() != ids.size()) throw new OutboxStorageException("OUTBOX_EXHAUSTED_UPDATE_MISMATCH");
        return changed.size();
    }

    private List<OutboxRecord> updateSafeClaims(List<Long> ids, String channel, String token, long leaseMillis) {
        if (ids.isEmpty()) return List.of();
        List<Object> arguments = new ArrayList<>();
        arguments.add(token);
        arguments.add(leaseMillis);
        arguments.add(channel);
        arguments.addAll(ids);
        List<OutboxRecord> changed = jdbc.query("UPDATE " + table + " SET status='PROCESSING', "
                        + "attempt_count=attempt_count+1, locked_by=?, locked_until=clock_timestamp()+(? * interval '1 millisecond'), "
                        + "updated_at=clock_timestamp() WHERE channel=? AND id IN (" + placeholders(ids.size())
                        + ") AND attempt_count < max_attempts "
                        + "AND ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp()) "
                        + "OR (status='PROCESSING' AND locked_until <= clock_timestamp())) RETURNING " + COLUMNS,
                rowMapper, arguments.toArray());
        if (changed.size() != ids.size()) throw new OutboxStorageException("OUTBOX_CLAIM_UPDATE_MISMATCH");
        return changed;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> instant(rs, "clock_timestamp"));
    }

    private void requireSafeDependencies() {
        if (objectMapper == null || retryPolicy == null) {
            throw new OutboxConfigurationException("OUTBOX_SAFE_STORE_DEPENDENCIES_REQUIRED");
        }
    }

    private void requireSafeMode() {
        requireSafeDependencies();
        DispatchMode mode = properties.getDispatch().getMode();
        if (mode != DispatchMode.LOCAL && mode != DispatchMode.COORDINATED) {
            throw new OutboxConfigurationException("OUTBOX_SAFE_DISPATCH_MODE_REQUIRED");
        }
    }

    private void requireConsumption() {
        if (!properties.getDispatch().isConsumptionEnabled()) {
            throw new OutboxConfigurationException("OUTBOX_CONSUMPTION_DISABLED");
        }
    }

    private void requireLegacyClaim() {
        requireConsumption();
        if (properties.getDispatch().getMode() != DispatchMode.LEGACY) {
            throw new OutboxConfigurationException("OUTBOX_LEGACY_CLAIM_DISABLED");
        }
    }

    private static void requireSlot(int slot) {
        if (slot != 0) throw new IllegalArgumentException("OUTBOX_JDBC_ID_OR_SLOT_INVALID");
    }

    private TransactionTemplate safeTransaction() {
        TransactionTemplate template = new TransactionTemplate(workerTransaction.getTransactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        long timeoutMillis = millis(properties.getCoordination().getClaimTimeout(), true, "OUTBOX_CLAIM_TIMEOUT_INVALID");
        template.setTimeout(Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, (timeoutMillis + 999) / 1000))));
        return template;
    }

    private void configureStatementTimeout() {
        long timeout = millis(properties.getCoordination().getClaimTimeout(), true, "OUTBOX_CLAIM_TIMEOUT_INVALID");
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, timeout + "ms");
    }

    private <T> T inSafeTransaction(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        long maximum = properties.getCoordination().getClaimTimeout().toNanos();
        try {
            return safeTransaction().execute(status -> {
                transactionGuard.requireSelectedTransaction();
                configureStatementTimeout();
                T result = action.get();
                if (System.nanoTime() - started >= maximum) {
                    throw new OutboxStorageException("OUTBOX_CLAIM_TRANSACTION_TIMEOUT");
                }
                return result;
            });
        } catch (DataAccessException | TransactionException failure) {
            throw new OutboxStorageException(operation, failure);
        }
    }

    private void validate(NewOutboxRecord record) {
        Objects.requireNonNull(record, "record");
        if (invalid(record.messageId(), 64) || optionalInvalid(record.idempotencyKey(), 256)
                || record.messageFingerprint() == null || !record.messageFingerprint().matches("[0-9a-f]{64}")
                || invalid(record.channel(), 64) || invalid(record.destination(), 256)
                || invalid(record.contentType(), 128) || optionalInvalid(record.schemaVersion(), 32)
                || optionalInvalid(record.traceId(), 128) || record.payload() == null || record.maxAttempts() < 1) {
            throw new OutboxValidationException("Invalid JDBC outbox record");
        }
        Map<String, String> parsed = headers.parseHeaders(record.headersJson());
        validator.validateSerialized(new SerializedOutboxPayload(record.payload(),
                record.payload().getBytes(StandardCharsets.UTF_8).length), parsed);
    }

    private OutboxRecord read(ResultSet rs, int row) throws SQLException {
        try {
            return new OutboxRecord(rs.getLong("id"), rs.getString("message_id"), rs.getString("idempotency_key"),
                    rs.getString("message_fingerprint"), rs.getString("channel"), rs.getString("destination"),
                    rs.getString("payload"), rs.getString("content_type"), rs.getString("schema_version"),
                    headers.parseHeaders(rs.getString("headers_json")), rs.getString("trace_id"),
                    OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                    instant(rs, "next_attempt_at"), rs.getString("locked_by"), instant(rs, "locked_until"),
                    rs.getString("last_error_code"), rs.getString("last_error_message"), instant(rs, "created_at"),
                    instant(rs, "updated_at"), instant(rs, "completed_at"));
        } catch (OutboxValidationException | IllegalArgumentException invalid) {
            throw new OutboxStorageException("Stored outbox message is invalid", invalid);
        }
    }

    private <T> T inWorkerTransaction(String operation, Supplier<T> action) {
        try { return workerTransaction.execute(status -> {
            transactionGuard.requireSelectedTransaction();
            return action.get();
        }); }
        catch (DataAccessException failure) { throw new OutboxStorageException(operation, failure); }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }
    private static boolean optionalInvalid(String value, int maximum) { return value != null && invalid(value, maximum); }
    private static void requireBatch(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("OUTBOX_BATCH_INVALID");
    }
    private static void requireOwner(String owner) {
        if (invalid(owner, 128)) throw new IllegalArgumentException("OUTBOX_LEASE_INVALID");
    }
    private static long millis(Duration value, boolean positive, String code) {
        if (value == null || value.isNegative() || (positive && value.isZero())) throw new IllegalArgumentException(code);
        try {
            long result = value.toMillis();
            if (positive && result < 1) throw new IllegalArgumentException(code);
            return result;
        } catch (ArithmeticException overflow) { throw new IllegalArgumentException(code); }
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static String sanitize(String value, int maximum) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        value.codePoints().forEach(codePoint -> {
            if (Character.charCount(codePoint) <= maximum - result.length()) {
                result.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            }
        });
        return result.toString();
    }
}
