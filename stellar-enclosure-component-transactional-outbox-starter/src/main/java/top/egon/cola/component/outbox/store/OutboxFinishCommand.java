package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
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
import top.egon.cola.component.outbox.delivery.DeliveryResult;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class OutboxFinishCommand {

    @PositiveOrZero
    private int shardSlot;
    @Positive
    private long id;
    @NotBlank
    @Size(max = 64)
    private String messageId;
    @Positive
    private int attemptCount;
    @NotBlank
    @Size(max = 128)
    private String claimToken;
    @NotNull
    @Valid
    private DeliveryResult deliveryResult;
}
