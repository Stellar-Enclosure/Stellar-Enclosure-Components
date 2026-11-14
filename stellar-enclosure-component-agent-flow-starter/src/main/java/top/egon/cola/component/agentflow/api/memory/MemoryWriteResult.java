package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryWriteResult {
    @NotBlank @Size(max = 128)
    private String factId;

    @Positive
    private long revision;

    @NotBlank @Size(max = 128)
    private String commandId;

    @NotNull @Pattern(regexp = "[0-9a-f]{64}")
    private String contentSha256;

    @NotNull
    private Instant updatedAt;
}
