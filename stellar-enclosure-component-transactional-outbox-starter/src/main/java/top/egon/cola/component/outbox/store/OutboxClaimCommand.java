package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Duration;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class OutboxClaimCommand {

    @NotNull
    @Valid
    private OutboxQueueKey queueKey;
    @Positive
    @Max(10_000)
    private int limit;
    @Valid
    private OutboxQueueLease queueLease;
    @NotBlank
    @Size(max = 128)
    private String claimToken;
    @NotNull
    private Duration leaseDuration;
    @PositiveOrZero
    private int dueQuota;
    @PositiveOrZero
    private int reclaimQuota;
}
