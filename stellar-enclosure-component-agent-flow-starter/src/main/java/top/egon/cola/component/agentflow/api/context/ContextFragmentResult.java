package top.egon.cola.component.agentflow.api.context;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class ContextFragmentResult {

    public static final int MAX_BYTES = 64 * 1024;

    @NotNull
    private List<@Valid FragmentVO> fragments;

    private String unavailableReason;

    public List<FragmentVO> checkedFragments() {
        if (fragments == null || fragments.size() > 32) {
            throw new IllegalArgumentException("Invalid context result count");
        }
        List<FragmentVO> checked = List.copyOf(fragments);
        if (checked.stream().mapToInt(fragment ->
                fragment.content().getBytes(StandardCharsets.UTF_8).length).sum() > MAX_BYTES
                || (unavailableReason != null && (!checked.isEmpty()
                    || !unavailableReason.matches("[A-Z][A-Z0-9_]{0,63}")))) {
            throw new IllegalArgumentException("Invalid context result");
        }
        return checked;
    }

    public record FragmentVO(String fragmentId, String sourceRef, long revision, String content,
                             String digest, Instant observedAt) {

        public FragmentVO {
            ContextScopeVO.requireIdentifier(fragmentId);
            ContextScopeVO.requireIdentifier(sourceRef);
            if (revision <= 0 || content == null || content.length() > 8192
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
                    || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid context fragment");
            }
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }
}
