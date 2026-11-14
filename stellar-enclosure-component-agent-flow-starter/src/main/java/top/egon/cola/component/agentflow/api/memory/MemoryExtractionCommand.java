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
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.NativeCodec;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.SourceRefVO;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class MemoryExtractionCommand {

    @NotNull @Size(max = 32)
    private List<@NotNull @Valid SourceFragmentVO> sourceFragments;

    @NotNull
    private String strategyRef;

    @Positive
    private long strategyVersion;

    public MemoryExtractionCommand snapshot() {
        List<SourceFragmentVO> sources = sourceFragments;
        String strategy = strategyRef;
        long version = strategyVersion;
        ContextScopeVO.requireIdentifier(strategy);
        if (sources == null || sources.size() > 32 || version <= 0 || version > 999_999_999_999_999L) {
            throw new IllegalArgumentException("Invalid Memory extraction command");
        }
        sources = List.copyOf(sources);
        var ids = new HashSet<String>();
        if (!sources.stream().allMatch(source -> ids.add(source.sourceRef()))) {
            throw new IllegalArgumentException("Duplicate Memory extraction source");
        }
        byte[] payload = JsonValues.canonicalBytes(Map.of("sourceFragments",
                sources.stream().map(SourceFragmentVO::wire).toList()));
        // Strip the fixed object member around the canonical source array.
        if (payload.length - "{\"sourceFragments\":}".length() > 64 * 1024) {
            throw new IllegalArgumentException("Memory extraction sources exceed their byte limit");
        }
        return new MemoryExtractionCommand(sources, strategy, version);
    }

    public record SourceFragmentVO(@NotNull String sourceRef, @Positive long sourceRevision,
                                   @NotNull String contentSha256, @NotNull Instant observedAt,
                                   @NotNull Map<String, Object> content) {

        public SourceFragmentVO {
            new SourceRefVO(sourceRef, sourceRevision, contentSha256);
            if (observedAt == null || observedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("Invalid Memory extraction observation time");
            }
            content = JsonValues.copyObject(content, 64 * 1024);
        }

        SourceRefVO evidence() {
            return new SourceRefVO(sourceRef, sourceRevision, contentSha256);
        }

        void requireExactContent() {
            if (!contentSha256.equals(NativeCodec.sourceContentSha256(content))) {
                throw MemoryStore.Access.denied("MEMORY_SOURCE_DENIED");
            }
        }

        private Map<String, Object> wire() {
            return new java.util.TreeMap<>(Map.of("sourceRef", sourceRef, "sourceRevision", sourceRevision,
                    "contentSha256", contentSha256, "observedAt", observedAt.toString(), "content", content));
        }
    }
}
