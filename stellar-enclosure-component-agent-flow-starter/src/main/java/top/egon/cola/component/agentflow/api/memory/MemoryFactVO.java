package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;

import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Map;

public record MemoryFactVO(@NotNull String factId, @NotNull MemoryFactTypeEnum type, @Positive long revision,
                           Map<String, Object> content, List<@NotNull @Valid ProvenanceVO> provenance,
                           boolean tombstone) {

    public MemoryFactVO {
        ContextScopeVO.requireIdentifier(factId);
        Objects.requireNonNull(type, "Memory fact type");
        if (revision < 1 || (tombstone ? (content != null || provenance != null)
                : (content == null || provenance == null || provenance.size() > 32))) {
            throw new IllegalArgumentException("Invalid Memory fact shape");
        }
        if (!tombstone) {
            // The authorized HOST reader also projects historical content through this neutral value.
            content = JsonValues.copyObject(content, 64 * 1024);
            provenance = List.copyOf(provenance);
        }
    }

    public record ProvenanceVO(@NotNull String sourceRef, @Positive Long sourceRevision,
                               @NotNull String contentSha256, Instant observedAt) {

        public ProvenanceVO {
            ContextScopeVO.requireIdentifier(sourceRef);
            if ((sourceRevision != null && sourceRevision <= 0) || contentSha256 == null
                    || !contentSha256.matches("[a-f0-9]{64}")
                    || (observedAt != null && observedAt.getNano() % 1_000 != 0)) {
                throw new IllegalArgumentException("Invalid Memory provenance");
            }
        }
    }
}
