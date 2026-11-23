package top.egon.cola.component.outbox.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class OutboxQueueCoordinationCommand {

    @PositiveOrZero
    private int shardSlot;
    @NotBlank
    @Size(max = 128)
    private String instanceId;
    @NotNull
    @Size(max = 128)
    @Builder.Default
    private Set<@NotBlank @Size(max = 64) String> channels = Set.of();
    @NotNull
    @Size(max = 128)
    @Builder.Default
    private Set<@NotBlank @Size(max = 64) String> acquire = Set.of();
    @NotNull
    @Size(max = 128)
    @Builder.Default
    private List<@NotNull @Valid OutboxQueueLease> renew = List.of();
    @NotNull
    @Size(max = 128)
    @Builder.Default
    private List<@NotNull @Valid OutboxQueueLease> release = List.of();
    private boolean draining;
}
