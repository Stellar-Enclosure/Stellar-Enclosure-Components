package top.egon.cola.component.outbox.store;

import java.time.Instant;
import java.util.Map;

public record OutboxRecord(
        long id,
        String messageId,
        String idempotencyKey,
        String messageFingerprint,
        String channel,
        String destination,
        String payload,
        String contentType,
        String schemaVersion,
        Map<String, String> headers,
        String traceId,
        OutboxStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        int shardSlot
) {
    public OutboxRecord(long id, String messageId, String idempotencyKey, String messageFingerprint,
                        String channel, String destination, String payload, String contentType,
                        String schemaVersion, Map<String, String> headers, String traceId, OutboxStatus status,
                        int attemptCount, int maxAttempts, Instant nextAttemptAt, String lockedBy, Instant lockedUntil,
                        String lastErrorCode, String lastErrorMessage, Instant createdAt, Instant updatedAt,
                        Instant completedAt) {
        this(id, messageId, idempotencyKey, messageFingerprint, channel, destination, payload, contentType,
                schemaVersion, headers, traceId, status, attemptCount, maxAttempts, nextAttemptAt, lockedBy,
                lockedUntil, lastErrorCode, lastErrorMessage, createdAt, updatedAt, completedAt, 0);
    }
}
