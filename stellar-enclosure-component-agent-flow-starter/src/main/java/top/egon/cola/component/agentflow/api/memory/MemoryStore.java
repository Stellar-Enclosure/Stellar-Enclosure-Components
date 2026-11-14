package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public interface MemoryStore {

    String CAPABILITY_REF = "memory";
    String MODEL_CAPABILITY_REF = "memory:model-egress";
    String READ_PURPOSE = "MEMORY_READ";
    String MODEL_READ_PURPOSE = "MEMORY_MODEL_EGRESS";
    String UPSERT_PURPOSE = "MEMORY_UPSERT";
    String DELETE_PURPOSE = "MEMORY_DELETE";
    String EXTRACT_PURPOSE = "MEMORY_EXTRACT";

    @Valid @NotNull MemoryRetrievalResult retrieve(@Valid @NotNull MemoryQuery query, @Valid @NotNull MemoryScopeVO scope,
                                                    @Valid @NotNull CallPermitVO permit);

    @Valid @NotNull MemoryWriteResult upsert(@Valid @NotNull MemoryWriteCommand command, @Valid @NotNull MemoryScopeVO scope,
                                             @Valid @NotNull CallPermitVO permit);

    @Valid @NotNull MemoryWriteResult delete(@Valid @NotNull MemoryDeleteCommand command, @Valid @NotNull MemoryScopeVO scope,
                                             @Valid @NotNull CallPermitVO permit);

    /**
     * Per-call authority supplied only by the trusted host. Implementations read current local policy;
     * these callbacks may run while the scope is fenced. They must avoid network I/O and store reentry.
     */
    interface MemoryAuthority extends CallPermitVO.Recheck {

        ReadPolicyVO authorizeRead(MemoryScopeVO scope, String purpose);

        WriteMetadataVO authorizeWrite(MemoryScopeVO scope, MemoryWriteCommand command);

        void authorizeDelete(MemoryScopeVO scope, MemoryDeleteCommand command);

        ExtractionPolicyVO authorizeExtraction(MemoryScopeVO scope, String strategyRef, long strategyVersion);
    }

    /** Closed pre-limit filter. Empty sets deny that dimension. */
    record ReadPolicyVO(Set<MemoryFactTypeEnum> types, Set<SourceRefVO> allowedSources, Set<String> sensitivities,
                        Set<String> retentionPolicyRefs, boolean allowWithoutSources, boolean modelEgress) {

        public ReadPolicyVO {
            types = Set.copyOf(types);
            allowedSources = Set.copyOf(allowedSources);
            sensitivities = Set.copyOf(sensitivities);
            retentionPolicyRefs = Set.copyOf(retentionPolicyRefs);
            sensitivities.forEach(ContextScopeVO::requireIdentifier);
            retentionPolicyRefs.forEach(MemoryStore::requireRetentionPolicyRef);
        }

        public boolean allows(MemoryScopeVO scope, String purpose, MemoryFactVO fact, WriteMetadataVO metadata) {
            boolean ordinaryRead = READ_PURPOSE.equals(purpose);
            boolean historicalHostRead = ordinaryRead && scope.retentionScope() == MemoryScopeVO.RetentionScopeEnum.HOST;
            if (!(ordinaryRead || MODEL_READ_PURPOSE.equals(purpose) && modelEgress)
                    || !types.contains(fact.type()) || !sensitivities.contains(metadata.sensitivity())
                    || !retentionPolicyRefs.contains(metadata.retentionPolicyRef())) {
                return false;
            }
            return fact.provenance().isEmpty() ? allowWithoutSources : fact.provenance().stream()
                    .allMatch(source -> (historicalHostRead || source.sourceRevision() != null && source.observedAt() != null)
                            && allowedSources.contains(
                                    new SourceRefVO(source.sourceRef(), source.sourceRevision(), source.contentSha256())));
        }
    }

    /** Only ordinary HOST history may authorize evidence with an unknown source revision. */
    record SourceRefVO(String sourceRef, Long sourceRevision, String contentSha256) {

        public SourceRefVO {
            ContextScopeVO.requireIdentifier(sourceRef);
            if (sourceRevision != null && sourceRevision <= 0 || contentSha256 == null
                    || !contentSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid Memory source evidence");
            }
        }
    }

    record WriteMetadataVO(boolean confirmed, Instant effectiveAt, Instant expiresAt, String sensitivity,
                           String retentionPolicyRef) {

        public WriteMetadataVO {
            ContextScopeVO.requireIdentifier(sensitivity);
            requireRetentionPolicyRef(retentionPolicyRef);
            if (effectiveAt != null && effectiveAt.getNano() % 1_000 != 0
                    || expiresAt != null && expiresAt.getNano() % 1_000 != 0
                    || effectiveAt != null && expiresAt != null && !effectiveAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException("Invalid Memory retention window");
            }
        }

        public WriteMetadataVO effectiveAtCommit(Instant now) {
            return effectiveAt == null ? new WriteMetadataVO(confirmed, now, expiresAt, sensitivity, retentionPolicyRef) : this;
        }

        public static WriteMetadataVO nativeDefaults() {
            return new WriteMetadataVO(true, null, null, "INTERNAL", "default");
        }
    }

    record ExtractionPolicyVO(Set<SourceRefVO> allowedSources) {

        public ExtractionPolicyVO {
            allowedSources = Set.copyOf(allowedSources);
            if (allowedSources.stream().anyMatch(source -> source.sourceRevision() == null)) {
                throw new IllegalArgumentException("Memory extraction requires exact source revisions");
            }
        }
    }

    private static void requireRetentionPolicyRef(String ref) {
        // HOST policies retain the full legacy identifier without normalizing it.
        if (ref == null || ref.isBlank() || ref.length() > 256 || ref.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(ref)) {
            throw new IllegalArgumentException("Invalid Memory retention policy reference");
        }
    }

    /** Shared digest implementation for native memory values and original operation receipts. */
    final class NativeCodec {

        private NativeCodec() {
        }

        public static String contentSha256(Map<String, Object> content) {
            return content == null
                    ? hash("EGON_AGENT_MEMORY_CONTENT_V1", "null".getBytes(StandardCharsets.UTF_8))
                    : digest("EGON_AGENT_MEMORY_CONTENT_V1", JsonValues.copyObject(content, 16 * 1024));
        }

        public static String sourceContentSha256(Map<String, Object> content) {
            return digest("EGON_AGENT_MEMORY_EXTRACTION_SOURCE_V1", JsonValues.copyObject(content, 64 * 1024));
        }

        public static String upsertFingerprint(String scopeHash, MemoryWriteCommand command, WriteMetadataVO metadata) {
            Map<String, Object> payload = commandPayload(scopeHash, "UPSERT", command.getFactId(),
                    command.getCommandId(), command.getExpectedRevision());
            payload.put("type", command.getType().code());
            payload.put("content", command.getContent());
            payload.put("provenance", provenancePayload(command.getProvenance()));
            Map<String, Object> approved = new TreeMap<>();
            approved.put("confirmed", metadata.confirmed());
            approved.put("effectiveAt", metadata.effectiveAt() == null ? null : metadata.effectiveAt().toString());
            approved.put("expiresAt", metadata.expiresAt() == null ? null : metadata.expiresAt().toString());
            approved.put("sensitivity", metadata.sensitivity());
            approved.put("retentionPolicyRef", metadata.retentionPolicyRef());
            payload.put("approvedMetadata", approved);
            return digest("EGON_AGENT_MEMORY_COMMAND_V1", payload);
        }

        public static String deleteFingerprint(String scopeHash, MemoryDeleteCommand command) {
            return digest("EGON_AGENT_MEMORY_COMMAND_V1", commandPayload(scopeHash, "DELETE", command.getFactId(),
                    command.getCommandId(), command.getExpectedRevision()));
        }

        private static Map<String, Object> commandPayload(String scopeHash, String operation, String factId,
                                                          String commandId, long expectedRevision) {
            return new TreeMap<>(Map.of("scopeHash", scopeHash, "namespace", "NATIVE", "operation", operation,
                    "factId", factId, "commandId", commandId, "expectedRevision", expectedRevision));
        }

        static int provenanceByteCount(List<MemoryFactVO.ProvenanceVO> sources) {
            int bytes = JsonValues.canonicalBytes(Map.of("provenance", provenancePayload(sources))).length;
            return bytes - "{\"provenance\":}".length();
        }

        private static List<Map<String, Object>> provenancePayload(List<MemoryFactVO.ProvenanceVO> sources) {
            return sources.stream().map(source -> {
                Map<String, Object> value = new TreeMap<>();
                value.put("sourceRef", source.sourceRef());
                value.put("sourceRevision", source.sourceRevision());
                value.put("contentSha256", source.contentSha256());
                value.put("observedAt", source.observedAt() == null ? null : source.observedAt().toString());
                return value;
            }).toList();
        }

        static String digest(String domain, Map<String, ?> canonicalObject) {
            return hash(domain, JsonValues.canonicalBytes(canonicalObject));
        }

        private static String hash(String domain, byte[] bytes) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                for (byte[] part : List.of(domain.getBytes(StandardCharsets.UTF_8), bytes)) {
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
                    digest.update(part);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }
    }

    /** Shared policy errors for adapters so authorization failures cannot become empty results. */
    final class Access {

        private Access() {
        }

        public static MemoryAuthority requireAuthority(CallPermitVO permit) {
            if (permit.recheck() instanceof MemoryAuthority authority) {
                return authority;
            }
            throw denied("MEMORY_AUTHORITY_REQUIRED");
        }

        public static void requireCurrent(CallPermitVO permit, String scopeHash, String capability, String purpose, Clock clock) {
            Objects.requireNonNull(permit, "Memory permit").requireCurrent(scopeHash, capability, purpose, true, clock);
        }

        public static CommonException denied(String code) {
            return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
        }
    }
}
