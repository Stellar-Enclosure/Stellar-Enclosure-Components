package top.egon.cola.component.outbox.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record OutboxQueueKey(@PositiveOrZero int shardSlot, @NotBlank @Size(max = 64) String channel) {
    public OutboxQueueKey {
        if (shardSlot < 0 || channel == null || channel.isBlank() || channel.length() > 64
                || !channel.equals(channel.strip())) {
            throw new IllegalArgumentException("OUTBOX_QUEUE_KEY_INVALID");
        }
    }
}
