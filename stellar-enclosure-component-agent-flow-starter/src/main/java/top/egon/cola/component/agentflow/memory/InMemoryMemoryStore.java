package top.egon.cola.component.agentflow.memory;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryDeleteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO;
import top.egon.cola.component.agentflow.api.memory.MemoryQuery;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult.HitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO.RetentionScopeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteResult;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class InMemoryMemoryStore implements MemoryStore {

    private static final long INITIAL_EPOCH = 1L;
    private static final WriteMetadataVO NATIVE_DEFAULTS = WriteMetadataVO.nativeDefaults();

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("defaultValidator")
    private final Validator validator;

    private final Map<String, ScopeSlot> slots = new ConcurrentHashMap<>();

    @Override
    public MemoryRetrievalResult retrieve(MemoryQuery query, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(query, "query").snapshot();
        validate(fixed);
        var access = access(scope, permit);
        boolean forModel = MODEL_CAPABILITY_REF.equals(permit.capabilityRef());
        String capability = forModel ? MODEL_CAPABILITY_REF : CAPABILITY_REF;
        String purpose = forModel ? MODEL_READ_PURPOSE : READ_PURPOSE;
        access.requireCurrent(capability, purpose);
        ScopeSlot slot = slots.get(access.hash());
        if (slot == null) {
            readPolicy(access, capability, purpose, forModel);
            return new MemoryRetrievalResult(List.of());
        }
        synchronized (slot) {
            slot.requireScope(scope);
            ReadPolicyVO policy = readPolicy(access, capability, purpose, forModel);
            Instant now = now();
            var items = slot.heads.values().stream()
                    .filter(head -> head.recallable(now))
                    .filter(head -> fixed.getTypes().isEmpty() || fixed.getTypes().contains(head.fact().type()))
                    .filter(head -> policy == null ? head.hasDefaultVisibility()
                            : policy.allows(scope, purpose, head.fact(), head.metadata()))
                    .filter(head -> matchesText(head, fixed.getText()))
                    .limit(fixed.getLimit())
                    .map(head -> head.hit(now))
                    .toList();
            access.requireCurrent(capability, purpose);
            return new MemoryRetrievalResult(items);
        }
    }

    @Override
    public MemoryWriteResult upsert(MemoryWriteCommand command, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(command, "command").snapshot();
        validate(fixed);
        var access = access(scope, permit);
        access.requireCurrent(CAPABILITY_REF, UPSERT_PURPOSE);
        ScopeSlot slot = slots.get(access.hash());
        if (slot == null) {
            // Denied first writes must not allocate a scope.
            writeMetadata(access, fixed);
            if (fixed.getExpectedRevision() != 0) {
                throw Access.denied("VERSION_CONFLICT");
            }
            slot = slots.computeIfAbsent(access.hash(), ignored -> new ScopeSlot(scope));
        }
        synchronized (slot) {
            slot.requireScope(scope);
            access.requireCurrent(CAPABILITY_REF, UPSERT_PURPOSE);
            WriteMetadataVO approved = writeMetadata(access, fixed);
            String fingerprint = NativeCodec.upsertFingerprint(access.hash(), fixed, approved);
            ReceiptVO original = slot.receipt(fixed.getCommandId(), fingerprint, access.authority() != null);
            if (original != null) {
                access.requireCurrent(CAPABILITY_REF, UPSERT_PURPOSE);
                return original.result();
            }
            RevisionVO prior = slot.heads.get(fixed.getFactId());
            requireMutationVisibility(access, prior);

            long priorRevision = prior == null ? 0 : prior.fact().revision();
            if (priorRevision != fixed.getExpectedRevision()) {
                throw Access.denied("VERSION_CONFLICT");
            }
            if (prior != null && prior.fact().type() != fixed.getType()) {
                throw Access.denied("TYPE_CONFLICT");
            }
            if (prior != null && prior.fact().tombstone()) {
                throw Access.denied("NOT_FOUND");
            }

            Instant now = now();
            WriteMetadataVO applied = approved.effectiveAtCommit(now);
            var fact = new MemoryFactVO(fixed.getFactId(), fixed.getType(), Math.addExact(priorRevision, 1),
                    fixed.getContent(), fixed.getProvenance(), false);
            boolean protectedHead = !NATIVE_DEFAULTS.equals(approved);
            boolean protectedReceipt = protectedHead || prior != null && prior.protectedMutation();
            var issue = new ReceiptVO(fingerprint, fact.factId(), fact.revision(), fixed.getCommandId(),
                    NativeCodec.contentSha256(fact.content()), now, protectedReceipt);
            access.requireCurrent(CAPABILITY_REF, UPSERT_PURPOSE);
            return slot.append(fact, applied, issue, protectedHead);
        }
    }

    @Override
    public MemoryWriteResult delete(MemoryDeleteCommand command, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(command, "command").snapshot();
        validate(fixed);
        var access = access(scope, permit);
        access.requireCurrent(CAPABILITY_REF, DELETE_PURPOSE);
        ScopeSlot slot = slots.get(access.hash());
        if (slot == null) {
            checkDeletePolicy(access, fixed);
            throw Access.denied("NOT_FOUND");
        }
        synchronized (slot) {
            slot.requireScope(scope);
            checkDeletePolicy(access, fixed);
            String fingerprint = NativeCodec.deleteFingerprint(access.hash(), fixed);
            ReceiptVO original = slot.receipt(fixed.getCommandId(), fingerprint, access.authority() != null);
            if (original != null) {
                access.requireCurrent(CAPABILITY_REF, DELETE_PURPOSE);
                return original.result();
            }
            RevisionVO prior = slot.heads.get(fixed.getFactId());
            requireMutationVisibility(access, prior);

            if (prior == null || prior.fact().tombstone()) {
                throw Access.denied("NOT_FOUND");
            }
            if (prior.fact().revision() != fixed.getExpectedRevision()) {
                throw Access.denied("VERSION_CONFLICT");
            }
            var tombstone = new MemoryFactVO(fixed.getFactId(), prior.fact().type(),
                    Math.addExact(prior.fact().revision(), 1), null, null, true);
            var issue = new ReceiptVO(fingerprint, tombstone.factId(), tombstone.revision(), fixed.getCommandId(),
                    NativeCodec.contentSha256(null), now(), prior.protectedMutation());
            access.requireCurrent(CAPABILITY_REF, DELETE_PURPOSE);
            return slot.append(tombstone, prior.metadata(), issue, prior.protectedMutation());
        }
    }

    private static void requireMutationVisibility(ScopeAccess access, RevisionVO prior) {
        if (access.authority() == null && prior != null && prior.protectedMutation()) {
            throw protectedState();
        }
    }

    private static RuntimeException protectedState() {
        log.debug("Memory operation denied code=NOT_FOUND");
        return Access.denied("NOT_FOUND");
    }

    private static boolean defaultVisible(WriteMetadataVO metadata) {
        return NATIVE_DEFAULTS.sensitivity().equals(metadata.sensitivity())
                && NATIVE_DEFAULTS.retentionPolicyRef().equals(metadata.retentionPolicyRef());
    }

    private ReadPolicyVO readPolicy(ScopeAccess access, String capability, String purpose, boolean forModel) {
        access.requireCurrent(capability, purpose);
        if (forModel && access.authority() == null) {
            throw Access.denied("MEMORY_AUTHORITY_REQUIRED");
        }
        access.requireStandalone();
        if (access.authority() == null) {
            return null;
        }
        ReadPolicyVO policy = access.authority().authorizeRead(access.scope(), purpose);
        if (policy == null || (forModel && !policy.modelEgress())) {
            throw Access.denied(forModel ? "MEMORY_EGRESS_DENIED" : "MEMORY_READ_DENIED");
        }
        access.requireCurrent(capability, purpose);
        return policy;
    }

    private WriteMetadataVO writeMetadata(ScopeAccess access, MemoryWriteCommand fixed) {
        access.requireStandalone();
        if (access.authority() == null) {
            return NATIVE_DEFAULTS;
        }
        var approved = access.authority().authorizeWrite(access.scope(), new MemoryWriteCommand(fixed.getFactId(),
                fixed.getExpectedRevision(), fixed.getCommandId(), fixed.getType(), fixed.getContent(), fixed.getProvenance()));
        if (approved == null) {
            throw Access.denied("MEMORY_WRITE_DENIED");
        }
        access.requireCurrent(CAPABILITY_REF, UPSERT_PURPOSE);
        return approved;
    }

    private void checkDeletePolicy(ScopeAccess access, MemoryDeleteCommand fixed) {
        access.requireCurrent(CAPABILITY_REF, DELETE_PURPOSE);
        access.requireStandalone();
        if (access.authority() != null) {
            access.authority().authorizeDelete(access.scope(),
                    new MemoryDeleteCommand(fixed.getFactId(), fixed.getExpectedRevision(), fixed.getCommandId()));
            access.requireCurrent(CAPABILITY_REF, DELETE_PURPOSE);
        }
    }

    private ScopeAccess access(MemoryScopeVO scope, CallPermitVO permit) {
        validate(scope);
        validate(permit);
        return new ScopeAccess(scope, scope.hash(), permit);
    }

    private <T> void validate(T input) {
        var violations = validator.validate(Objects.requireNonNull(input, "Memory boundary"));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean matchesText(RevisionVO revision, String text) {
        return text == null || text.isBlank() || containsText(revision.fact().content(), text);
    }

    private static boolean containsText(Object value, String query) {
        if (value instanceof String text) {
            return text.contains(query);
        }
        if (value instanceof Map<?, ?> object) {
            return object.values().stream().anyMatch(member -> containsText(member, query));
        }
        return value instanceof List<?> list && list.stream().anyMatch(element -> containsText(element, query));
    }

    private final class ScopeAccess {

        private final MemoryScopeVO scope;
        private final String hash;
        private final CallPermitVO permit;

        private ScopeAccess(MemoryScopeVO scope, String hash, CallPermitVO permit) {
            this.scope = scope;
            this.hash = hash;
            this.permit = permit;
        }

        MemoryScopeVO scope() {
            return scope;
        }

        String hash() {
            return hash;
        }

        MemoryAuthority authority() {
            return permit.recheck() instanceof MemoryAuthority typed ? typed : null;
        }

        void requireCurrent(String capability, String purpose) {
            Access.requireCurrent(permit, hash, capability, purpose, clock);
            if (scope.retentionScope() != RetentionScopeEnum.HOST && permit.authorityEpoch() != INITIAL_EPOCH) {
                throw Access.denied("MEMORY_AUTHORITY_EXPIRED");
            }
        }

        void requireStandalone() {
            if (scope.retentionScope() == RetentionScopeEnum.HOST) {
                Access.requireAuthority(permit);
                throw Access.denied("MEMORY_UNAVAILABLE");
            }
        }
    }

    private static final class ScopeSlot {

        private final MemoryScopeVO scope;
        private final Map<String, RevisionVO> heads = new TreeMap<>();
        private final Map<String, ReceiptVO> receipts = new HashMap<>();

        private ScopeSlot(MemoryScopeVO scope) {
            this.scope = scope;
        }

        void requireScope(MemoryScopeVO candidate) {
            if (!scope.equals(candidate)) {
                throw Access.denied("MEMORY_SCOPE_MISMATCH");
            }
        }

        ReceiptVO receipt(String commandId, String fingerprint, boolean typedAuthority) {
            ReceiptVO receipt = receipts.get(commandId);
            if (receipt != null) {
                if (receipt.protectedState() && !typedAuthority) {
                    throw protectedState();
                }
                if (!receipt.fingerprint().equals(fingerprint)) {
                    throw Access.denied("COMMAND_CONFLICT");
                }
            }
            return receipt;
        }

        MemoryWriteResult append(MemoryFactVO fact, WriteMetadataVO metadata, ReceiptVO receipt, boolean protectedMutation) {
            var entry = new RevisionVO(fact, metadata, protectedMutation);
            heads.put(fact.factId(), entry);
            receipts.put(receipt.commandId(), receipt);
            return receipt.result();
        }
    }

    private record RevisionVO(MemoryFactVO fact, WriteMetadataVO metadata, boolean protectedMutation) {

        boolean hasDefaultVisibility() {
            return defaultVisible(metadata);
        }

        boolean recallable(Instant now) {
            return !fact.tombstone() && metadata.confirmed() && !now.isBefore(metadata.effectiveAt())
                    && (metadata.expiresAt() == null || now.isBefore(metadata.expiresAt()));
        }

        HitVO hit(Instant now) {
            return new HitVO(fact.factId(), fact.type(), fact.revision(), fact.content(), fact.provenance(), null, now);
        }
    }

    private record ReceiptVO(String fingerprint, String factId, long revision, String commandId, String contentSha256,
                             Instant committedAt, boolean protectedState) {

        MemoryWriteResult result() {
            return new MemoryWriteResult(factId, revision, commandId, contentSha256, committedAt);
        }
    }
}
