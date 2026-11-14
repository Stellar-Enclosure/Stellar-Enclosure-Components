package top.egon.cola.component.agentflow.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryCandidateResult;
import top.egon.cola.component.agentflow.api.memory.MemoryCandidateResult.CandidateVO;
import top.egon.cola.component.agentflow.api.memory.MemoryDeleteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryExtractionCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryExtractionCommand.SourceFragmentVO;
import top.egon.cola.component.agentflow.api.memory.MemoryExtractionStrategy;
import top.egon.cola.component.agentflow.api.memory.MemoryFactTypeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO.ProvenanceVO;
import top.egon.cola.component.agentflow.api.memory.MemoryQuery;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO.RetentionScopeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.ExtractionPolicyVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.MemoryAuthority;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.NativeCodec;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.ReadPolicyVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.SourceRefVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.WriteMetadataVO;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteCommand;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MemoryStoreConformanceTest {

    private static final Instant START = Instant.parse("2026-11-10T00:00:00.123456Z");
    private static final String DIGEST = "a".repeat(64);
    private final MutableClock clock = new MutableClock();
    private final jakarta.validation.ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final MemoryStore store = new InMemoryMemoryStore(clock, validators.getValidator());
    private final MemoryScopeVO scope = scope("tenant-a");

    @AfterEach
    void closeValidationFactory() {
        validators.close();
    }

    @Test
    void canonicalScopeUsesExactNamespaceAndAllSevenMembers() {
        assertThat(scope.canonicalScope()).containsOnlyKeys("hostScopeKey", "instanceKey", "namespace",
                "principalKey", "retentionScope", "sessionKey", "tenantKey");
        assertThat(scope.canonicalScope()).containsEntry("sessionKey", null).containsEntry("hostScopeKey", null);
        assertThat(scope.hash()).isEqualTo("f9ff2ecf26cc7fd5601aa0aa98f7c9b23183d13a3b8e05df3d4eceb388e8691c");
        assertThat(scope("tenant-b").hash()).isNotEqualTo(scope.hash());
        assertThat(new MemoryScopeVO("应用", "tenant-a", null, "principal", "instance", null, null)).isEqualTo(scope);
        assertThatThrownBy(() -> new MemoryScopeVO("app", "tenant", RetentionScopeEnum.USER, "principal", "instance",
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryScopeVO("app", "tenant", RetentionScopeEnum.SESSION, "principal", "instance",
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryScopeVO("app", "tenant", RetentionScopeEnum.HOST, null, null,
                null, "界")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MemoryScopeVO("app", "tenant", RetentionScopeEnum.HOST, null, null,
                null, "a".repeat(2048)).hash()).hasSize(64);
    }

    @Test
    void exactReplayPreservesOriginalAfterUpdateDeleteAndCallerMutation() {
        var nested = new ArrayList<>(List.of("first"));
        var firstCommand = command("item", 0, "create", MemoryFactTypeEnum.FACT, Map.of("list", nested), "source-a");
        var first = store.upsert(firstCommand, scope, permit(scope, 1));
        String originalHash = first.getContentSha256();
        first.setContentSha256("f".repeat(64));
        nested.add("caller mutation");
        clock.at(START.plusSeconds(1));
        store.upsert(command("item", 1, "update", MemoryFactTypeEnum.FACT, Map.of("list", List.of("second")),
                "source-a"), scope, permit(scope, 1));
        clock.at(START.plusSeconds(2));
        var deletion = new MemoryDeleteCommand("item", 2, "delete");
        var deleted = store.delete(deletion, scope, permit(scope, 1));

        var replay = store.upsert(command("item", 0, "create", MemoryFactTypeEnum.FACT,
                Map.of("list", List.of("first")), "source-a"), scope, permit(scope, 1));
        assertThat(replay.getRevision()).isEqualTo(1);
        assertThat(replay.getUpdatedAt()).isEqualTo(START);
        assertThat(replay.getContentSha256()).isEqualTo(originalHash);
        assertThat(store.delete(deletion, scope, permit(scope, 1))).isEqualTo(deleted);
        assertThat(store.retrieve(query(10), scope, permit(scope, 1)).getItems()).isEmpty();
        assertThatThrownBy(() -> store.upsert(firstCommand, scope, permit(scope, 1))).hasMessageContaining("COMMAND_CONFLICT");
        assertThatThrownBy(() -> store.upsert(command("other", 0, "create", MemoryFactTypeEnum.FACT,
                Map.of("list", List.of("first")), "source-a"), scope, permit(scope, 1))).hasMessageContaining("COMMAND_CONFLICT");
        assertThatThrownBy(() -> store.delete(new MemoryDeleteCommand("item", 3, "create"), scope, permit(scope, 1)))
                .hasMessageContaining("COMMAND_CONFLICT");
    }

    @Test
    void filtersAllEligibilityBeforeLimitAndNeverResurrectsDeletedHead() {
        put("a-unconfirmed", MemoryFactTypeEnum.FACT, "public", metadata(false, null, null, "PUBLIC", "standard"));
        put("b-future", MemoryFactTypeEnum.FACT, "public", metadata(true, START.plusSeconds(2), null, "PUBLIC", "standard"));
        put("c-expired", MemoryFactTypeEnum.FACT, "public", metadata(true, START.minusSeconds(1), START, "PUBLIC", "standard"));
        put("d-other-type", MemoryFactTypeEnum.PREFERENCE, "public", metadata(true, null, null, "PUBLIC", "standard"));
        put("e-sensitive", MemoryFactTypeEnum.FACT, "public", metadata(true, null, null, "SECRET", "standard"));
        put("f-source-denied", MemoryFactTypeEnum.FACT, "private", metadata(true, null, null, "PUBLIC", "standard"));
        put("g-retention-denied", MemoryFactTypeEnum.FACT, "public", metadata(true, null, null, "PUBLIC", "restricted"));
        put("h-deleted", MemoryFactTypeEnum.FACT, "public", metadata(true, null, null, "PUBLIC", "standard"));
        store.delete(new MemoryDeleteCommand("h-deleted", 1, "deleted"), scope,
                permit(scope, 1, new Authority(null, null, Set.of(), new AtomicBoolean(true))));
        put("i-visible", MemoryFactTypeEnum.FACT, "public", metadata(true, START, START.plusSeconds(1), "PUBLIC", "standard"));

        var policy = new ReadPolicyVO(Set.of(MemoryFactTypeEnum.FACT), Set.of(new SourceRefVO("public", 1L, DIGEST)),
                Set.of("PUBLIC"), Set.of("standard"), true, false);
        var readPermit = permit(scope, 1, new Authority(policy, null, Set.of(), new AtomicBoolean(true)));
        var result = store.retrieve(query(1), scope, readPermit);
        assertThat(result.getItems()).extracting(MemoryRetrievalResult.HitVO::factId).containsExactly("i-visible");
        clock.at(START.plusSeconds(1));
        assertThat(store.retrieve(query(1), scope, readPermit).getItems()).isEmpty();
        clock.at(START.plusSeconds(2));
        assertThat(store.retrieve(query(1), scope, readPermit).getItems())
                .extracting(MemoryRetrievalResult.HitVO::factId).containsExactly("b-future");
    }

    @Test
    void verifiesPurposeAndScopeEpochBeforeLookupOrReplay() {
        store.upsert(command("item", 0, "create", MemoryFactTypeEnum.FACT, Map.of("text", "value"), "public"),
                scope, permit(scope, 1));
        var foreign = scope("tenant-b");
        assertThat(store.retrieve(query(10), foreign, permit(foreign, 1)).getItems()).isEmpty();
        assertThatThrownBy(() -> store.retrieve(query(10), scope, permit(foreign, 1))).hasMessageContaining("PERMIT_MISMATCH");
        assertThatThrownBy(() -> store.retrieve(query(10), scope, permit(scope, 2))).hasMessageContaining("EXPIRED");
        var expired = new CallPermitVO("permit", MemoryStore.CAPABILITY_REF, scope.hash(), 1, 1L, START,
                () -> false, (permit, purpose) -> true);
        assertThatThrownBy(() -> store.upsert(command("item", 0, "create", MemoryFactTypeEnum.FACT,
                Map.of("text", "value"), "public"), scope, expired)).hasMessageContaining("PERMIT_EXPIRED");
        assertThatThrownBy(() -> store.retrieve(query(10), scope, modelPermit(scope, (permit, purpose) -> true)))
                .hasMessageContaining("MEMORY_AUTHORITY_REQUIRED");
        var noEgress = new ReadPolicyVO(Set.of(), Set.of(), Set.of(), Set.of(), false, false);
        assertThatThrownBy(() -> store.retrieve(query(10), scope,
                modelPermit(scope, new Authority(noEgress, null, Set.of(), new AtomicBoolean(true)))))
                .hasMessageContaining("MEMORY_EGRESS_DENIED");
        var host = new MemoryScopeVO("app", "tenant", RetentionScopeEnum.HOST, null, null, null, "opaque");
        assertThatThrownBy(() -> store.retrieve(query(10), host, permit(host, 1))).hasMessageContaining("MEMORY_AUTHORITY_REQUIRED");
        assertThatThrownBy(() -> store.retrieve(query(10), host,
                permit(host, 1, new Authority(noEgress, null, Set.of(), new AtomicBoolean(true)))))
                .hasMessageContaining("MEMORY_UNAVAILABLE");
    }

    @Test
    void changedApprovedWriteMetadataConflictsAndTypeIsImmutable() {
        var command = command("item", 0, "create", MemoryFactTypeEnum.FACT, Map.of("text", "value"), "public");
        var first = new Authority(null, metadata(true, null, null, "PUBLIC", "standard"), Set.of(), new AtomicBoolean(true));
        var changed = new Authority(null, metadata(true, null, null, "INTERIOR", "standard"), Set.of(), new AtomicBoolean(true));
        store.upsert(command, scope, permit(scope, 1, first));
        assertThatThrownBy(() -> store.upsert(command, scope, permit(scope, 1, changed))).hasMessageContaining("COMMAND_CONFLICT");
        assertThatThrownBy(() -> store.upsert(command("item", 1, "type-change", MemoryFactTypeEnum.PREFERENCE,
                Map.of("text", "value"), "public"), scope, permit(scope, 1, first))).hasMessageContaining("TYPE_CONFLICT");
        assertThatThrownBy(() -> store.delete(new MemoryDeleteCommand("missing", 1, "delete"), scope, permit(scope, 1)))
                .hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> store.delete(new MemoryDeleteCommand("item", 2, "delete"), scope, permit(scope, 1, first)))
                .hasMessageContaining("VERSION_CONFLICT");
    }

    @Test
    void ordinaryReadPermitOnlyObservesDefaultNativeVisibility() {
        put("restricted", MemoryFactTypeEnum.FACT, "public", metadata(true, null, null, "RESTRICTED", "restricted"));
        store.upsert(command("visible", 0, "visible", MemoryFactTypeEnum.FACT, Map.of("text", "normal"), "public"),
                scope, permit(scope, 1));
        assertThat(store.retrieve(query(10), scope, permit(scope, 1)).getItems())
                .extracting(MemoryRetrievalResult.HitVO::factId).containsExactly("visible");
    }

    @Test
    void genericPermitCannotDowngradeDeleteOrReplayProtectedData() {
        var ordinary = permit(scope, 1);
        var protectedPolicy = new Authority(null, metadata(true, null, null, "RESTRICTED", "restricted"),
                Set.of(), new AtomicBoolean(true));
        var typed = permit(scope, 1, protectedPolicy);
        var create = command("protected", 0, "first", MemoryFactTypeEnum.FACT, Map.of("text", "secret"), "public");
        var first = store.upsert(create, scope, typed);
        assertThatThrownBy(() -> store.upsert(create, scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> store.upsert(command("protected", 1, "downgrade", MemoryFactTypeEnum.FACT,
                Map.of(), "public"), scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> store.delete(new MemoryDeleteCommand("protected", 1, "drop"), scope, ordinary))
                .hasMessageContaining("NOT_FOUND");
        assertThat(store.upsert(create, scope, typed)).isEqualTo(first);
        var deletion = new MemoryDeleteCommand("protected", 1, "drop");
        store.delete(deletion, scope, typed);
        assertThatThrownBy(() -> store.delete(deletion, scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> store.upsert(command("other", 0, "first", MemoryFactTypeEnum.FACT, Map.of(),
                "public"), scope, ordinary)).hasMessageContaining("NOT_FOUND");
    }

    @Test
    void genericPermitCannotOverrideHostLifecycleAndRecoversItsOriginalReceipt() {
        var ordinary = permit(scope, 1);
        var first = command("item", 0, "create", MemoryFactTypeEnum.FACT, Map.of("text", "ordinary"), "public");
        var receipt = store.upsert(first, scope, ordinary);
        var pending = metadata(false, null, START.plusSeconds(2), "INTERNAL", "default");
        var typed = permit(scope, 1, new Authority(null, pending, Set.of(), new AtomicBoolean(true)));
        store.upsert(command("item", 1, "pending", MemoryFactTypeEnum.FACT, Map.of("text", "pending"), "public"),
                scope, typed);
        assertThat(store.upsert(first, scope, ordinary)).isEqualTo(receipt);
        assertThat(store.retrieve(query(10), scope, ordinary).getItems()).isEmpty();
        assertThatThrownBy(() -> store.upsert(command("item", 2, "publish", MemoryFactTypeEnum.FACT,
                Map.of("text", "leak"), "public"), scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> store.delete(new MemoryDeleteCommand("item", 2, "drop"), scope, ordinary))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void hostHistoricalEvidenceIsExactAndRestrictedToOrdinaryRead() {
        var host = new MemoryScopeVO("app", "tenant", RetentionScopeEnum.HOST, null, null, null, "opaque");
        String retainedPolicy = "保".repeat(256);
        var metadata = metadata(true, START, null, "PUBLIC", retainedPolicy);
        var missingRevision = new SourceRefVO("legacy", null, DIGEST);
        var revisionKnown = new SourceRefVO("legacy", 1L, DIGEST);
        var policy = new ReadPolicyVO(Set.of(MemoryFactTypeEnum.FACT), Set.of(missingRevision, revisionKnown),
                Set.of("PUBLIC"), Set.of(retainedPolicy), false, true);
        var missingBoth = historicFact(null, null, DIGEST);
        var missingTime = historicFact(1L, null, DIGEST);
        var complete = historicFact(1L, START, DIGEST);
        assertThat(policy.allows(host, MemoryStore.READ_PURPOSE, missingBoth, metadata)).isTrue();
        assertThat(policy.allows(host, MemoryStore.READ_PURPOSE, missingTime, metadata)).isTrue();
        assertThat(policy.allows(host, MemoryStore.READ_PURPOSE, historicFact(null, null, "b".repeat(64)),
                metadata)).isFalse();
        assertThat(policy.allows(scope, MemoryStore.READ_PURPOSE, missingBoth, metadata)).isFalse();
        assertThat(policy.allows(host, MemoryStore.MODEL_READ_PURPOSE, missingBoth, metadata)).isFalse();
        assertThat(policy.allows(host, MemoryStore.MODEL_READ_PURPOSE, missingTime, metadata)).isFalse();
        assertThat(policy.allows(host, MemoryStore.MODEL_READ_PURPOSE, complete, metadata)).isTrue();
        assertThatThrownBy(() -> new ExtractionPolicyVO(Set.of(missingRevision)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metadata(true, START, null, "PUBLIC", retainedPolicy + "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadPolicyVO(Set.of(), Set.of(), Set.of(), Set.of(retainedPolicy + "a"), false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesUnorderedRawContentCanonicallyAndKeepsMaximumContentDepth() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("z", 1);
        raw.put("a", "one");
        assertThat(NativeCodec.contentSha256(raw)).isEqualTo(NativeCodec.contentSha256(Map.of("a", "one", "z", 1L)));
        Map<String, Object> deepest = Map.of("leaf", true);
        for (int level = 0; level < 7; level++) {
            deepest = Map.of("next", deepest);
        }
        var command = command("deep", 0, "deep", MemoryFactTypeEnum.FACT, deepest, "public");
        var committed = store.upsert(command, scope, permit(scope, 1));
        assertThat(committed.getContentSha256()).isEqualTo(NativeCodec.contentSha256(deepest));
        assertThat(store.upsert(command, scope, permit(scope, 1))).isEqualTo(committed);
    }

    @Test
    void concurrentChangesCannotBothWinTheSameRevision() throws Exception {
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> attemptCreate("first", start));
            var second = workers.submit(() -> attemptCreate("second", start));
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "VERSION_CONFLICT");
        }
    }

    @Test
    void rejectsDuplicateUnknownAndNullWireTypesBeforeUse() throws Exception {
        var mapper = new ObjectMapper();
        String prefix = "{\"limit\":1,\"types\":";
        for (String bad : List.of("null", "[null]", "[\"FACT\",\"FACT\"]", "[\"UNKNOWN\"]")) {
            assertThatThrownBy(() -> mapper.readValue(prefix + bad + "}", MemoryQuery.class))
                    .as("types %s", bad)
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
        assertThatThrownBy(() -> mapper.readValue("{\"limit\":1}", MemoryQuery.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThat(mapper.readValue(prefix + "[\"FACT\"]}", MemoryQuery.class).getTypes())
                .containsExactly(MemoryFactTypeEnum.FACT);
    }

    @Test
    void guardedExtractionValidatesExactSourcesBeforeAndAfterAndWritesNothing() {
        var calls = new AtomicInteger();
        var alive = new AtomicBoolean(true);
        var content = Map.<String, Object>of("text", "candidate");
        String extractionDigest = NativeCodec.sourceContentSha256(content);
        var read = new SourceRefVO("public", 1L, extractionDigest);
        var trusted = new Authority(null, null, Set.of(read), alive);
        var extraction = new MemoryExtractionCommand(List.of(new SourceFragmentVO("public", 1, extractionDigest, START, content)), "extract", 1);
        var strategy = MemoryExtractionStrategy.guarded(clock, validators.getValidator(), (command, guardedScope, guardedPermit) -> {
            calls.incrementAndGet();
            return new MemoryCandidateResult(List.of(new CandidateVO(null, MemoryFactTypeEnum.EXPERIENCE,
                    Map.of("text", "candidate"), List.of("public"), "0.5")));
        });
        var extractionPermit = new CallPermitVO("permit", "extract", scope.hash(), 1, 1L, START.plusSeconds(20),
                () -> false, trusted);
        assertThat(strategy.extract(extraction, scope, extractionPermit).getCandidates()).hasSize(1);
        assertThat(store.retrieve(query(10), scope, permit(scope, 1)).getItems()).isEmpty();

        var wrong = new MemoryExtractionCommand(List.of(new SourceFragmentVO("public", 2, extractionDigest, START, content)), "extract", 1);
        assertThatThrownBy(() -> strategy.extract(wrong, scope, extractionPermit)).hasMessageContaining("MEMORY_SOURCE_DENIED");
        var changedBody = new MemoryExtractionCommand(List.of(new SourceFragmentVO("public", 1, extractionDigest,
                START, Map.of("text", "substitution"))), "extract", 1);
        assertThatThrownBy(() -> strategy.extract(changedBody, scope, extractionPermit))
                .hasMessageContaining("MEMORY_SOURCE_DENIED");
        var wrongOutput = MemoryExtractionStrategy.guarded(clock, validators.getValidator(), (input, exactScope, grant) ->
                new MemoryCandidateResult(List.of(new CandidateVO(null, MemoryFactTypeEnum.FACT, Map.of(),
                        List.of("unapproved"), null))));
        assertThatThrownBy(() -> wrongOutput.extract(extraction, scope, extractionPermit)).hasMessageContaining("INVALID_OUTPUT");
        var revokedInFlight = MemoryExtractionStrategy.guarded(clock, validators.getValidator(), (input, exactScope, grant) -> {
            alive.set(false);
            return new MemoryCandidateResult(List.of());
        });
        assertThatThrownBy(() -> revokedInFlight.extract(extraction, scope, extractionPermit)).hasMessageContaining("PERMIT_REVOKED");
        assertThat(calls).hasValue(1);
    }

    private String attemptCreate(String id, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            store.upsert(command("same", 0, id, MemoryFactTypeEnum.FACT, Map.of("id", id), "public"), scope, permit(scope, 1));
            return "COMMITTED";
        } catch (CommonException failure) {
            return failure.getStatus();
        }
    }

    private void put(String id, MemoryFactTypeEnum type, String source, WriteMetadataVO metadata) {
        store.upsert(command(id, 0, id, type, Map.of("text", "example"), source), scope,
                permit(scope, 1, new Authority(null, metadata, Set.of(), new AtomicBoolean(true))));
    }

    private static MemoryScopeVO scope(String tenant) {
        return new MemoryScopeVO("应用", tenant, RetentionScopeEnum.INSTANCE, "principal", "instance", null, null);
    }

    private static MemoryFactVO historicFact(Long sourceRevision, Instant observedAt, String digest) {
        return new MemoryFactVO("item", MemoryFactTypeEnum.FACT, 1, Map.of("text", "legacy"),
                List.of(new ProvenanceVO("legacy", sourceRevision, digest, observedAt)), false);
    }

    private static CallPermitVO permit(MemoryScopeVO scope, long epoch) {
        return permit(scope, epoch, (ignored, purpose) -> true);
    }

    private static CallPermitVO permit(MemoryScopeVO scope, long epoch, CallPermitVO.Recheck authority) {
        return new CallPermitVO("permit", MemoryStore.CAPABILITY_REF, scope.hash(), 1, epoch,
                START.plusSeconds(60), () -> false, authority);
    }

    private static CallPermitVO modelPermit(MemoryScopeVO scope, CallPermitVO.Recheck authority) {
        return new CallPermitVO("permit", MemoryStore.MODEL_CAPABILITY_REF, scope.hash(), 1, 1L,
                START.plusSeconds(60), () -> false, authority);
    }

    private static MemoryQuery query(int limit) {
        return new MemoryQuery(null, limit, Set.of());
    }

    private static WriteMetadataVO metadata(boolean confirmed, Instant effective, Instant expires,
                                            String sensitivity, String retentionPolicyRef) {
        return new WriteMetadataVO(confirmed, effective, expires, sensitivity, retentionPolicyRef);
    }

    private static MemoryWriteCommand command(String factId, long revision, String key, MemoryFactTypeEnum type,
                                              Map<String, Object> content, String source) {
        return new MemoryWriteCommand(factId, revision, key, type, content,
                List.of(new ProvenanceVO(source, 1L, DIGEST, START)));
    }

    private record Authority(ReadPolicyVO read, WriteMetadataVO write, Set<SourceRefVO> sources,
                             AtomicBoolean live) implements MemoryAuthority {
        @Override
        public boolean isCurrent(CallPermitVO permit, String purpose) {
            return live.get();
        }

        @Override
        public ReadPolicyVO authorizeRead(MemoryScopeVO scope, String purpose) {
            return read;
        }

        @Override
        public WriteMetadataVO authorizeWrite(MemoryScopeVO scope, MemoryWriteCommand command) {
            return write;
        }

        @Override
        public void authorizeDelete(MemoryScopeVO scope, MemoryDeleteCommand command) {
        }

        @Override
        public ExtractionPolicyVO authorizeExtraction(MemoryScopeVO scope, String strategyRef, long strategyVersion) {
            return new ExtractionPolicyVO(sources);
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void at(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
