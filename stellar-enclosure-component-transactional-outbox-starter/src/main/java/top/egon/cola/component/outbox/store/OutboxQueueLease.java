package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record OutboxQueueLease(
        @NotNull @Valid OutboxQueueKey key,
        @NotBlank @Size(max = 128) String instanceId,
        @Positive long epoch,
        @NotNull Instant expiresAt
) {
    public OutboxQueueLease {
        if (key == null || instanceId == null || instanceId.isBlank() || instanceId.length() > 128
                || epoch <= 0 || expiresAt == null) {
            throw new IllegalArgumentException("OUTBOX_QUEUE_LEASE_INVALID");
        }
    }
}
