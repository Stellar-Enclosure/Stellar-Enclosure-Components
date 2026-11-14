package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryDeleteCommand {
    @NotBlank @Size(max = 128)
    private String factId;

    @Positive
    private long expectedRevision;

    @NotBlank @Size(max = 128)
    private String commandId;

    public MemoryDeleteCommand snapshot() {
        String fact = factId;
        long expected = expectedRevision;
        String key = commandId;
        ContextScopeVO.requireIdentifier(fact);
        ContextScopeVO.requireIdentifier(key);
        if (expected < 1) {
            throw new IllegalArgumentException("Invalid Memory delete revision");
        }
        return new MemoryDeleteCommand(fact, expected, key);
    }
}
