package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.outbox.api.OutboxReceipt;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

public interface OutboxStore {

    default void selectShard(String group) {
        if (group != null) {
            throw new UnsupportedOperationException("OUTBOX_EXPLICIT_SHARD_UNSUPPORTED");
        }
    }

    default String prepareGeneratedMessageId(String generated) {
        return generated;
    }

    OutboxReceipt enqueue(@NotNull @Valid NewOutboxRecord record);

    List<OutboxRecord> claimDue(
            @Positive @Max(10_000) int limit,
            @NotNull String leaseOwner,
            @NotNull Duration leaseDuration
    );

    List<OutboxRecord> claimByMessageIds(
            Collection<String> messageIds,
            @Positive @Max(10_000) int limit,
            @NotNull String leaseOwner,
            @NotNull Duration leaseDuration
    );

    boolean markSucceeded(@Positive long id, @NotNull String leaseOwner);

    default boolean markSucceeded(@Positive long id, @NotNull String leaseOwner, int shardSlot) {
        return markSucceeded(id, leaseOwner);
    }

    boolean markRetry(
            @Positive long id,
            @NotNull String leaseOwner,
            @NotNull Duration delay,
            String errorCode,
            String errorMessage
    );

    boolean markDead(@Positive long id, @NotNull String leaseOwner, String errorCode, String errorMessage);

    default boolean markRetry(@Positive long id, @NotNull String leaseOwner, @NotNull Duration delay,
                              String errorCode, String errorMessage, int shardSlot) {
        return markRetry(id, leaseOwner, delay, errorCode, errorMessage);
    }

    default boolean markDead(@Positive long id, @NotNull String leaseOwner, String errorCode, String errorMessage, int shardSlot) {
        return markDead(id, leaseOwner, errorCode, errorMessage);
    }

    int deleteSucceeded(@NotNull Duration retention, @Positive @Max(10_000) int limit);

    long countBacklog();

    void validateSchema();
}
