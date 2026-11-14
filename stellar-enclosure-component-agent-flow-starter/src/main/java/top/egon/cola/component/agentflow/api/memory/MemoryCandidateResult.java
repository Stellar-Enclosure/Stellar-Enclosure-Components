package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryCandidateResult {

    @NotNull @Size(max = 32)
    private List<@NotNull @Valid CandidateVO> candidates;

    public record CandidateVO(String proposedFactId, @NotNull MemoryFactTypeEnum type,
                              @NotNull Map<String, Object> content, @NotNull List<@NotNull String> sourceRefs,
                              String confidence) {

        public CandidateVO {
            if (proposedFactId != null) {
                ContextScopeVO.requireIdentifier(proposedFactId);
            }
            if (type == null || sourceRefs == null || sourceRefs.size() > 32
                    || confidence != null && !confidence.matches("(0(\\.[0-9]{1,6})?|1(\\.0{1,6})?)")) {
                throw new IllegalArgumentException("Invalid Memory extraction candidate");
            }
            sourceRefs = List.copyOf(sourceRefs);
            sourceRefs.forEach(ContextScopeVO::requireIdentifier);
            if (new HashSet<>(sourceRefs).size() != sourceRefs.size()) {
                throw new IllegalArgumentException("Duplicate Memory candidate source");
            }
            content = JsonValues.copyObject(content, 16 * 1024);
        }
    }

    MemoryCandidateResult permittedProjection(Set<String> allowedSources) {
        List<CandidateVO> values = candidates;
        if (values == null || values.size() > 32) {
            throw MemoryStore.Access.denied("INVALID_OUTPUT");
        }
        values = List.copyOf(values);
        if (values.stream().anyMatch(candidate -> !allowedSources.containsAll(candidate.sourceRefs()))) {
            throw MemoryStore.Access.denied("INVALID_OUTPUT");
        }
        return new MemoryCandidateResult(values);
    }
}
