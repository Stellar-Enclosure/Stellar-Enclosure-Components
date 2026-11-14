package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO.ProvenanceVO;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryRetrievalResult {
    @NotNull @Size(max = 50)
    private List<@NotNull @Valid HitVO> items;

    public record HitVO(@NotNull String factId, @NotNull MemoryFactTypeEnum type, @Positive long revision,
                        @NotNull Map<String, Object> content,
                        @NotNull List<@NotNull @Valid ProvenanceVO> provenance,
                        String score, @NotNull Instant observedAt) {

        public HitVO {
            var fact = new MemoryFactVO(factId, type, revision, content, provenance, false);
            content = fact.content();
            provenance = fact.provenance();
            Objects.requireNonNull(observedAt, "Memory observation time");
            if (observedAt.getNano() % 1_000 != 0
                    || (score != null && !score.matches("-?(0|[1-9][0-9]{0,14})(\\.[0-9]{1,18})?"))) {
                throw new IllegalArgumentException("Invalid Memory retrieval hit");
            }
        }
    }
}
