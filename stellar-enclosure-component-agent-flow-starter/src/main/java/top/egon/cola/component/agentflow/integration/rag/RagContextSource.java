package top.egon.cola.component.agentflow.integration.rag;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult.FragmentVO;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.memory.MemoryFactTypeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.rag.api.RagRetrievalService;
import top.egon.cola.component.rag.model.RagRetrievalQuery;
import top.egon.cola.component.rag.model.RagRetrievedChunkBO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional semantic retrieval over currently authorized exact Memory heads. */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class RagContextSource implements ContextSource {

    private static final int MAX_HEADS = 50;
    private static final int MAX_INDEXED_CHUNKS = 200;
    private static final Comparator<RankedEvidenceVO> BEST_FIRST = Comparator
            .comparingDouble(RankedEvidenceVO::score).reversed()
            .thenComparing(value -> value.head().factId()).thenComparing(value -> value.chunk().chunkId());

    @Qualifier("ragRetrievalService") private final RagRetrievalService retrieval;
    @Qualifier("agentMemoryRagAuthority") private final Authority authority;
    @Qualifier("agentExecutionClock") private final Clock clock;
    @Qualifier("defaultValidator") private final Validator validator;

    @Override
    public ContextFragmentResult resolve(ContextQuery query, ContextScopeVO scope, CallPermitVO permit) {
        Objects.requireNonNull(query, "RAG context query");
        var fixed = new ContextQuery(query.getSourceRef(), query.getPurpose(), query.getMaxCharacters(), query.getMaxFragments());
        var violations = validator.validate(fixed);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        ContextScopeVO.requireIdentifier(fixed.getSourceRef());
        ContextScopeVO.requireIdentifier(fixed.getPurpose());
        current(scope, permit, fixed);

        List<RankedEvidenceVO> ranked = new ArrayList<>();
        List<FragmentVO> fragments;
        try {
            if (!authority.supportsExactPrefilter(retrieval)) throw unavailable();
            SearchGrantVO grant = Objects.requireNonNull(authority.authorize(scope, fixed.getSourceRef(), fixed.getPurpose(), permit), "RAG grant");
            requireScope(scope, grant.scope());
            current(scope, permit, fixed);
            for (HeadGrantVO head : grant.eligibleHeads()) {
                Map<String, String> filter = Map.of(
                        "memoryScopeSha256", grant.scope().hash(), "memoryAuthorityEpoch", Long.toString(grant.authorityEpoch()),
                        "memoryFactId", head.factId(), "memoryFactRevision", Long.toString(head.revision()), "memoryFactType",
                        head.type().code(), "memorySensitivity", head.sensitivity(), "memoryRetention", head.retentionPolicyRef());
                var matches = retrieval.retrieve(new RagRetrievalQuery(grant.collectionId(), grant.logicalModel(), grant.text(),
                        head.indexedChunkCount(), grant.threshold(), filter));
                current(scope, permit, fixed);
                addRanked(ranked, matches, grant, head, filter);
            }
            fragments = confirm(ranked, grant, fixed, scope, permit);
        } catch (RuntimeException failure) {
            // A changed permit remains a permission error, and unprovable index/authority is never an empty search.
            current(scope, permit, fixed);
            log.debug("Agent Flow semantic memory unavailable");
            return new ContextFragmentResult(List.of(), "MEMORY_UNAVAILABLE");
        }
        current(scope, permit, fixed);
        return new ContextFragmentResult(fragments, null);
    }

    private static void addRanked(List<RankedEvidenceVO> ranked, List<RagRetrievedChunkBO> matches, SearchGrantVO grant, HeadGrantVO head,
                                  Map<String, String> filter) {
        if (matches == null || matches.size() > head.indexedChunkCount()) throw unavailable();
        var chunkIds = new HashSet<String>();
        for (var chunk : matches) {
            Double score = chunk.score();
            if (!grant.collectionId().equals(chunk.collectionId()) || !grant.logicalModel().equals(chunk.logicalModelName())
                    || chunk.chunkId() == null || !chunkIds.add(chunk.chunkId())
                    || score == null || !Double.isFinite(score) || score < grant.threshold() || score > 1
                    || !filter.entrySet().stream().allMatch(entry -> entry.getValue().equals(chunk.attributes().get(entry.getKey())))) {
                throw unavailable();
            }
            ContextScopeVO.requireIdentifier(chunk.chunkId());
            ContextScopeVO.requireIdentifier(chunk.documentId());
            ranked.add(new RankedEvidenceVO(head, new RankedChunkVO(chunk.documentId(), chunk.chunkId(), chunk.chunkIndex()), score));
        }
    }

    private List<FragmentVO> confirm(List<RankedEvidenceVO> ranked, SearchGrantVO grant, ContextQuery query,
                                     ContextScopeVO scope, CallPermitVO permit) {
        var accepted = new ArrayList<FragmentVO>(query.getMaxFragments());
        var ids = new HashSet<String>();
        int remaining = ContextFragmentResult.MAX_BYTES;
        ranked.sort(BEST_FIRST);
        for (var next : ranked) {
            if (accepted.size() == query.getMaxFragments()) break;
            current(scope, permit, query);
            FragmentVO exact = authority.confirm(scope, query.getSourceRef(), query.getPurpose(), permit, grant, next.head(), next.chunk());
            // A missing current head or revoked head-level grant filters the stale result.
            if (exact == null) continue;
            byte[] bytes = exact.content().getBytes(StandardCharsets.UTF_8);
            if (!query.getSourceRef().equals(exact.sourceRef()) || exact.revision() != next.head().revision()
                    || exact.content().indexOf(0) >= 0 || !sha256(bytes).equals(exact.digest())) throw unavailable();
            if (exact.content().length() > query.getMaxCharacters() || bytes.length > remaining) continue;
            if (ids.add(exact.fragmentId())) {
                accepted.add(exact);
                remaining -= bytes.length;
            }
        }
        return List.copyOf(accepted);
    }

    private static void requireScope(ContextScopeVO context, MemoryScopeVO memory) {
        if (!context.tenantKey().equals(memory.tenantKey())) throw unavailable();
        if (memory.retentionScope() == MemoryScopeVO.RetentionScopeEnum.HOST) {
            // The registered authority maps the opaque HOST scope to this exact context.
            return;
        }
        if (!context.namespace().equals(memory.namespace()) || !context.principalKey().equals(memory.principalKey())
                || memory.instanceKey() != null && !context.instanceKey().equals(memory.instanceKey())
                || memory.sessionKey() != null && !context.sessionKey().equals(memory.sessionKey())) throw unavailable();
    }

    private void current(ContextScopeVO scope, CallPermitVO permit, ContextQuery query) {
        permit.requireCurrent(scope, query.getSourceRef(), query.getPurpose(), clock);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("MEMORY_UNAVAILABLE");
    }

    public interface Authority {
        /** Verifies that the selected backend applies exact filters before limiting results and returns comparable scores. */
        boolean supportsExactPrefilter(RagRetrievalService backend);

        /**
         * Returns the complete bounded set of presently eligible heads and their verified chunk counts
         * for the selected collection and model. Rejects partial, stale or unprovable index coverage.
         */
        SearchGrantVO authorize(ContextScopeVO context, String sourceRef, String purpose, CallPermitVO permit);

        /** Rechecks current scope/epoch/head, retention, sensitivity and source access, and reads the authoritative fragment. */
        FragmentVO confirm(ContextScopeVO context, String sourceRef, String purpose, CallPermitVO permit, SearchGrantVO grant,
                           HeadGrantVO head, RankedChunkVO chunk);
    }

    public record SearchGrantVO(MemoryScopeVO scope, long authorityEpoch, String collectionId, String logicalModel,
                                String text, double threshold, List<HeadGrantVO> eligibleHeads) {
        public SearchGrantVO {
            Objects.requireNonNull(scope, "RAG memory scope");
            ContextScopeVO.requireIdentifier(collectionId);
            ContextScopeVO.requireIdentifier(logicalModel);
            if (authorityEpoch <= 0 || text == null || text.isBlank() || text.length() > 2000 || text.indexOf(0) >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(text) || !Double.isFinite(threshold)
                    || threshold < 0 || threshold > 1 || eligibleHeads == null || eligibleHeads.size() > MAX_HEADS) {
                throw unavailable();
            }
            eligibleHeads = List.copyOf(eligibleHeads);
            if (eligibleHeads.stream().map(HeadGrantVO::factId).distinct().count() != eligibleHeads.size()
                    || eligibleHeads.stream().mapToInt(HeadGrantVO::indexedChunkCount).sum() > MAX_INDEXED_CHUNKS) {
                throw unavailable();
            }
        }

        @Override public String toString() { return "SearchGrantVO"; }
    }

    public record HeadGrantVO(String factId, long revision, MemoryFactTypeEnum type, String sensitivity,
                              String retentionPolicyRef, int indexedChunkCount) {
        public HeadGrantVO {
            ContextScopeVO.requireIdentifier(factId);
            ContextScopeVO.requireIdentifier(sensitivity);
            if (revision <= 0 || type == null || indexedChunkCount < 1 || indexedChunkCount > MAX_INDEXED_CHUNKS
                    || retentionPolicyRef == null || retentionPolicyRef.isBlank()
                    || retentionPolicyRef.length() > 256 || retentionPolicyRef.codePoints().anyMatch(Character::isISOControl)
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(retentionPolicyRef)) throw unavailable();
        }
    }

    public record RankedChunkVO(String documentId, String chunkId, int chunkIndex) {
        public RankedChunkVO {
            if (chunkIndex < 0) throw unavailable();
        }
    }

    private record RankedEvidenceVO(HeadGrantVO head, RankedChunkVO chunk, double score) { }
}
