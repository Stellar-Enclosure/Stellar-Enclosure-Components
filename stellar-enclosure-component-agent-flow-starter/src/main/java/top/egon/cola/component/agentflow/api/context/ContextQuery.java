package top.egon.cola.component.agentflow.api.context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class ContextQuery {

    @NotBlank @Size(max = 128)
    private String sourceRef;

    @NotBlank @Size(max = 128)
    private String purpose;

    @Min(1) @Max(8192)
    @Builder.Default
    private int maxCharacters = 8192;

    @Min(1) @Max(32)
    @Builder.Default
    private int maxFragments = 32;
}
