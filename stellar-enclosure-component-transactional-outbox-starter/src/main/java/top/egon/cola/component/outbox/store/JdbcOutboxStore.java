package top.egon.cola.component.outbox.store;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.common.exception.OutboxValidationException;
import top.egon.cola.component.outbox.persistence.converter.OutboxHeadersConverter;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** PostgreSQL implementation for a single ordinary DataSource and its own transaction boundary. */
public final class JdbcOutboxStore implements OutboxStore {
    private static final int MAX_BATCH = 10_000;
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
    private final RowMapper<OutboxRecord> rowMapper = this::read;

    public JdbcOutboxStore(DataSource dataSource, String schema, TransactionTemplate workerTransaction,
                           OutboxTransactionGuard transactionGuard, OutboxLifecycleService lifecycle,
                           OutboxHeadersConverter headers, OutboxMessageValidator validator, ValidationUtils validationUtils) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("OUTBOX_JDBC_SCHEMA_INVALID");
        }
        this.schema = schema;
        this.table = '"' + schema + "\".\"egon_cola_outbox_message\"";
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
        return claim(null, limit, leaseOwner, leaseDuration);
    }

    @Override
    public List<OutboxRecord> claimByMessageIds(Collection<String> messageIds, int limit,
                                                 String leaseOwner, Duration leaseDuration) {
        if (messageIds == null || messageIds.isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>(messageIds);
        if (unique.size() > MAX_BATCH || unique.stream().anyMatch(id -> invalid(id, 64))) {
            throw new IllegalArgumentException("OUTBOX_MESSAGE_IDS_INVALID");
        }
        return claim(List.copyOf(unique), limit, leaseOwner, leaseDuration);
    }

    private List<OutboxRecord> claim(List<String> ids, int limit, String owner, Duration lease) {
        requireBatch(limit);
        requireOwner(owner);
        long leaseMillis = millis(lease, true, "OUTBOX_LEASE_INVALID");
        return inWorkerTransaction("Failed to claim outbox messages", () -> {
            String idFilter = ids == null ? "" : " AND message_id IN (" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
            List<Object> parameters = new ArrayList<>();
            if (ids != null) parameters.addAll(ids);
            parameters.add(limit);
            List<OutboxRecord> candidates = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= clock_timestamp())"
                            + " OR (status = 'PROCESSING' AND locked_until < clock_timestamp()))" + idFilter
                            + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    rowMapper, parameters.toArray());
            List<OutboxRecord> claimed = new ArrayList<>(candidates.size());
            for (OutboxRecord candidate : candidates) {
                OutboxLifecycleSignalEnum signal = candidate.status() == OutboxStatus.PROCESSING
                        ? OutboxLifecycleSignalEnum.RECLAIM : OutboxLifecycleSignalEnum.CLAIM;
                OutboxStatus target = lifecycle.evaluate(candidate.status().name(), signal,
                        candidate.attemptCount(), candidate.maxAttempts());
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
        return inWorkerTransaction("Failed to update outbox message", () -> {
            List<OutboxRecord> found = jdbc.query("SELECT " + COLUMNS + " FROM " + table
                            + " WHERE id=? AND status='PROCESSING' AND locked_by=? "
                            + "AND locked_until > clock_timestamp() FOR UPDATE", rowMapper, id, owner);
            if (found.isEmpty()) return false;
            OutboxRecord current = found.getFirst();
            OutboxStatus target = lifecycle.evaluate(current.status().name(), signal,
                    current.attemptCount(), current.maxAttempts());
            boolean retry = target == OutboxStatus.RETRY_WAIT;
            boolean succeeded = target == OutboxStatus.SUCCEEDED;
            return jdbc.update("UPDATE " + table + " SET status=?, locked_by=NULL, locked_until=NULL, "
                            + "next_attempt_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 millisecond') ELSE next_attempt_at END, "
                            + "last_error_code=?, last_error_message=?, completed_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END, "
                            + "updated_at=clock_timestamp() WHERE id=? AND status='PROCESSING' AND locked_by=? "
                            + "AND locked_until > clock_timestamp()",
                    target.name(), retry, delayMillis, succeeded ? null : sanitize(code, 64),
                    succeeded ? null : sanitize(message, 2000), retry, id, owner) == 1;
        });
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
        new JdbcOutboxSchemaValidator(jdbc, schema, validationUtils).validate();
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
