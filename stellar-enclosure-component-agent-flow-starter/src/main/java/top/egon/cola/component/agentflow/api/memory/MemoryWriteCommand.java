package top.egon.cola.component.agentflow.api.memory;

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
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO.ProvenanceVO;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryWriteCommand {
    @NotBlank @Size(max = 128)
    private String factId;

    @PositiveOrZero
    private long expectedRevision;

    @NotBlank @Size(max = 128)
    private String commandId;

    @NotNull
    private MemoryFactTypeEnum type;

    @NotNull
    private Map<String, Object> content;

    @NotNull @Size(max = 32)
    private List<@NotNull @Valid ProvenanceVO> provenance;

    public MemoryWriteCommand snapshot() {
        String fact = factId;
        String key = commandId;
        long expected = expectedRevision;
        MemoryFactTypeEnum factType = type;
        ContextScopeVO.requireIdentifier(fact);
        ContextScopeVO.requireIdentifier(key);
        List<ProvenanceVO> sources = provenance;
        if (factType == null || expected < 0 || sources == null || sources.size() > 32) {
            throw new IllegalArgumentException("Invalid native Memory write");
        }
        sources = List.copyOf(sources);
        if (sources.stream().anyMatch(source -> source.sourceRevision() == null || source.observedAt() == null)
                || MemoryStore.NativeCodec.provenanceByteCount(sources) > 16 * 1024) {
            throw new IllegalArgumentException("Invalid native Memory provenance");
        }
        return new MemoryWriteCommand(fact, expected, key, factType, JsonValues.copyObject(content, 16 * 1024), sources);
    }
}
