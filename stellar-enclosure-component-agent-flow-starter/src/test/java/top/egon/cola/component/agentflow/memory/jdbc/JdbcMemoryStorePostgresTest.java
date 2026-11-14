package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult.FragmentVO;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryDeleteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryFactTypeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult.HitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryQuery;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.ExtractionPolicyVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.MemoryAuthority;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.ReadPolicyVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.WriteMetadataVO;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteCommand;
import top.egon.cola.component.agentflow.integration.rag.RagContextSource;
import top.egon.cola.component.agentflow.integration.rag.RagContextSource.HeadGrantVO;
import top.egon.cola.component.agentflow.integration.rag.RagContextSource.SearchGrantVO;
import top.egon.cola.component.agentflow.memory.InMemoryMemoryStore;
import top.egon.cola.component.rag.api.RagRetrievalService;
import top.egon.cola.component.rag.autoconfigure.NoopRagMetricsRecorder;
import top.egon.cola.component.rag.autoconfigure.RagEmbeddingModelProperties;
import top.egon.cola.component.rag.autoconfigure.RagProperties;
import top.egon.cola.component.rag.embed.RagEmbeddingModelRegistry;
import top.egon.cola.component.rag.execution.RagRetrievalServiceImpl;
import top.egon.cola.component.rag.metadata.RagMetadataKeys;
import top.egon.cola.component.rag.model.RagRetrievedChunkBO;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(120)
@Isolated("Requires an explicitly provisioned fresh loopback PostgreSQL database")
class JdbcMemoryStorePostgresTest {

    private static final String URL_PROPERTY = "egon.agent.memory.postgres.test.url";
    private static final String RUNTIME_PROPERTY = "egon.agent.memory.postgres.test.runtime-user";
    private static final String SCHEMA = "egon_agent_memory";
    private static final String RESOURCE_ROOT = "/db/egon-agent-memory/";
    private static final String MIGRATION = "V1__create_memory_store.sql";
    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void semanticPrefilterIsExactAndOnlyAuthorityConfirmedContentReachesContext() throws Exception {
        var memory = scope("tenant-one");
        var context = new ContextScopeVO(memory.namespace(), memory.tenantKey(), "principal", "instance", "session", "SEMANTIC_MEMORY");
        var contextPermit = new CallPermitVO("rag-permit", "memory-index", context.scopeHash(), 1, null, Instant.now().plusSeconds(90),
                () -> false, (permit, purpose) -> true);
        var current = new HeadGrantVO("current", 2, MemoryFactTypeEnum.FACT, "INTERNAL", "default", 2);
        var stale = new HeadGrantVO("stale", 1, MemoryFactTypeEnum.PREFERENCE, "RESTRICTED", "selected", 1);
        var grant = new SearchGrantVO(memory, 1, "collection", "model", "fixed user intent", 0.2, List.of(stale, current));
        String text = "content from the exact authoritative head";
        String oversized = "x".repeat(201);
        var sha256 = MessageDigest.getInstance("SHA-256");
        String bodyDigest = HexFormat.of().formatHex(sha256.digest(text.getBytes(StandardCharsets.UTF_8)));
        String oversizedDigest = HexFormat.of().formatHex(sha256.digest(oversized.getBytes(StandardCharsets.UTF_8)));
        var trustedBackend = new AtomicReference<RagRetrievalService>();
        var authority = new RagContextSource.Authority() {
            @Override public boolean supportsExactPrefilter(RagRetrievalService backend) {
                return backend == trustedBackend.get();
            }

            @Override public SearchGrantVO authorize(ContextScopeVO request, String source, String purpose, CallPermitVO permit) {
                assertThat(request).isEqualTo(context);
                assertThat(source).isEqualTo("memory-index");
                assertThat(purpose).isEqualTo(context.purpose());
                return grant;
            }

            @Override public FragmentVO confirm(ContextScopeVO request, String source, String purpose, CallPermitVO permit, SearchGrantVO observed,
                    HeadGrantVO head, RagContextSource.RankedChunkVO chunk) {
                assertThat(observed).isEqualTo(grant);
                if (head.equals(stale)) {
                    return null;
                }
                boolean tooLong = "current-oversized".equals(chunk.chunkId());
                return new FragmentVO("approved-fragment", source, head.revision(), tooLong ? oversized : text,
                        tooLong ? oversizedDigest : bodyDigest, CLOCK.instant());
            }
        };
        var requests = new ArrayList<SearchRequest>();
        var vectorStore = mock(VectorStore.class);
        var records = List.of(
                vectorChunk(memory.hash(), stale, "stale-chunk", 0.9),
                vectorChunk(memory.hash(), current, "current-chunk", 0.3),
                vectorChunk(memory.hash(), current, "current-oversized", 0.8),
                vectorChunk(scope("foreign-tenant").hash(), current, "foreign-chunk", 0.99),
                vectorChunk(memory.hash(), new HeadGrantVO(current.factId(), 1, current.type(),
                        current.sensitivity(), current.retentionPolicyRef(), 1), "old-chunk", 0.95),
                vectorChunk(memory.hash(), new HeadGrantVO(current.factId(), current.revision(), current.type(),
                        "RESTRICTED", current.retentionPolicyRef(), 1), "restricted-chunk", 0.97));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            requests.add(request);
            return records.stream()
                    .filter(chunk -> matchesFilter(request.getFilterExpression(), chunk))
                    .sorted(Comparator.comparing(Document::getScore).reversed())
                    .limit(request.getTopK())
                    .toList();
        });
        var model = mock(EmbeddingModel.class);
        when(model.dimensions()).thenReturn(3);
        var properties = new RagProperties(true, 3, "hostVectorStore",
                Map.of("model", new RagEmbeddingModelProperties("hostEmbedding")), "model", null, null, null);
        var models = new RagEmbeddingModelRegistry(properties, new StaticListableBeanFactory(Map.of("hostEmbedding", model)));
        var retrieval = new RagRetrievalServiceImpl(models, vectorStore, properties, CLOCK, new NoopRagMetricsRecorder());
        trustedBackend.set(retrieval);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var rag = new RagContextSource(retrieval, authority, CLOCK, validators.getValidator());
            var query = new ContextQuery("memory-index", context.purpose(), 200, 1);
            var result = rag.resolve(query, context, contextPermit);
            assertThat(result.getUnavailableReason()).isNull();
            assertThat(result.checkedFragments()).extracting(FragmentVO::content).containsExactly(text);
            assertThat(requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.getSimilarityThreshold()).isEqualTo(0.2);
                assertThat(request.getFilterExpression()).isNotNull();
            });
            assertThat(requests).extracting(SearchRequest::getTopK).containsExactly(1, 2);
            assertThat(records.stream().filter(chunk -> matchesFilter(requests.getFirst().getFilterExpression(), chunk))
                    .map(Document::getId)).containsExactly("stale-chunk");
            assertThat(records.stream().filter(chunk -> matchesFilter(requests.getLast().getFilterExpression(), chunk))
                    .map(Document::getId)).containsExactly("current-chunk", "current-oversized");
            var capped = new RagContextSource(request -> {
                throw new AssertionError("An unverified vector backend must never be called");
            }, authority, CLOCK, validators.getValidator());
            assertThat(capped.resolve(query, context, contextPermit).getUnavailableReason()).isEqualTo("MEMORY_UNAVAILABLE");
            RagRetrievalService invalidEcho = request -> List.of(new RagRetrievedChunkBO("other-scope", request.collectionId(),
                    "document", 0, "never disclose", 0.4, request.logicalModelName(), Map.of()));
            trustedBackend.set(invalidEcho);
            var unavailable = new RagContextSource(invalidEcho, authority, CLOCK, validators.getValidator());
            assertThat(unavailable.resolve(query, context, contextPermit).getUnavailableReason()).isEqualTo("MEMORY_UNAVAILABLE");
            RagRetrievalService missingScore = request -> List.of(new RagRetrievedChunkBO("unscored", request.collectionId(),
                    "document", 0, "never disclose", null, request.logicalModelName(), request.attributes()));
            trustedBackend.set(missingScore);
            assertThat(new RagContextSource(missingScore, authority, CLOCK, validators.getValidator())
                    .resolve(query, context, contextPermit).getUnavailableReason()).isEqualTo("MEMORY_UNAVAILABLE");
        }
    }

    private static Document vectorChunk(String scopeHash, HeadGrantVO head, String chunkId, double score) {
        var metadata = RagMetadataKeys.toDocumentMetadata("collection", "document", "model", 0,
                Map.of("memoryScopeSha256", scopeHash, "memoryAuthorityEpoch", "1",
                        "memoryFactId", head.factId(), "memoryFactRevision", Long.toString(head.revision()),
                        "memoryFactType", head.type().code(), "memorySensitivity", head.sensitivity(),
                        "memoryRetention", head.retentionPolicyRef()));
        return new Document(chunkId, "untrusted vector body", metadata).mutate().score(score).build();
    }

    private static boolean matchesFilter(Filter.Expression filter, Document document) {
        return switch (filter.type()) {
            case AND -> matchesFilter((Filter.Expression) filter.left(), document)
                    && matchesFilter((Filter.Expression) filter.right(), document);
            case EQ -> document.getMetadata().get(((Filter.Key) filter.left()).key())
                    .equals(((Filter.Value) filter.right()).value());
            default -> throw new AssertionError("Unexpected vector filter " + filter.type());
        };
    }

    @Test
    void verifiedComponentMigrationSupportsAuthoritativeWritesAndReopensWithoutDdl() throws Exception {
        String url = System.getProperty(URL_PROPERTY, "");
        String runtime = System.getProperty(RUNTIME_PROPERTY, "");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/tianji_it_[a-z0-9_]{1,40}");
        assertThat(runtime).matches("tianji_it_runtime_[0-9a-f]{12}");
        try (var owner = DriverManager.getConnection(url);
             var limited = DriverManager.getConnection(url, runtime, "");
             var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertDedicatedDatabase(owner, limited, runtime);
            byte[] script = resource(MIGRATION);
            assertManifest(script);
            provision(owner, runtime, script);
            var source = source(url, runtime);
            verifyStorage(source, SCHEMA);
            assertThatThrownBy(() -> verifyStorage(source, "missing_memory_schema"))
                    .hasRootCauseMessage("MEMORY_STORAGE_UNAVAILABLE");
            var validator = validatorFactory.getValidator();
            var store = store(source, validator);
            var scope = scope("tenant-one");
            var permit = permit(scope, 1);
            var command = write("entry", "create", 0, "original");
            var first = store.upsert(command, scope, permit);
            assertThat(first.getRevision()).isEqualTo(1);
            assertThat(first.getUpdatedAt().getNano() % 1000).isZero();
            assertThat(first.getContentSha256()).isEqualTo(MemoryStore.NativeCodec.contentSha256(command.getContent()));

            var reopened = store(source(url, runtime), validator);
            assertThat(reopened.retrieve(query(), scope, permit).getItems()).hasSize(1);
            assertThat(reopened.retrieve(query(), scope("tenant-two"), permit(scope("tenant-two"), 1)).getItems()).isEmpty();
            assertThatThrownBy(() -> reopened.retrieve(query(), scope, permit(scope, 2))).hasMessageContaining("EXPIRED");
            assertThatThrownBy(() -> reopened.retrieve(query(), scope("tenant-two"), permit)).hasMessageContaining("PERMIT_MISMATCH");
            assertThatThrownBy(() -> reopened.upsert(write("entry", "create", 0, "changed"), scope, permit))
                    .hasMessageContaining("COMMAND_CONFLICT");

            var ready = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var left = pool.submit(() -> compete(store, scope, permit, "left", ready, release));
                var right = pool.submit(() -> compete(reopened, scope, permit, "right", ready, release));
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            }
            var deletion = new MemoryDeleteCommand("entry", 2, "delete");
            assertThat(reopened.delete(deletion, scope, permit).getRevision()).isEqualTo(3);
            assertThat(reopened.upsert(command, scope, permit)).isEqualTo(first);
            assertThat(reopened.retrieve(query(), scope, permit).getItems()).isEmpty();
            assertCount(limited, "scope", 1);
            assertCount(limited, "revision", 3);
            assertCount(limited, "fact", 1);
            assertCount(limited, "receipt", 3);
            try (var statement = limited.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM egon_agent_memory.receipt"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            }
            var parityScope = scope("jdbc-parity");
            verifySamePolicyBehavior(reopened, parityScope);
            verifyStoredNativePolicy(limited, parityScope);
            verifySamePolicyBehavior(new InMemoryMemoryStore(CLOCK, validator), scope("memory-parity"));
            verifyHostedFence(owner, source, (JdbcMemoryStore) store);
            verifyCatalogTamperFails(owner, runtime, source);
        }
    }

    private static void verifyStoredNativePolicy(Connection runtime, MemoryScopeVO scope) throws SQLException {
        try (var statement = runtime.prepareStatement("""
                SELECT fact_id,revision,state,confirmed,host_metadata IS NULL,
                       native_metadata->>'sensitivity', native_metadata->>'retentionPolicyRef',
                       native_metadata->>'protectedMutation', native_metadata->>'protectedReceipt'
                FROM egon_agent_memory.revision
                WHERE scope_hash = ?::char(64) COLLATE "C" AND fact_id IN ('a-pending','e-protected')
                ORDER BY fact_id,revision
                """)) {
            statement.setString(1, scope.hash());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("a-pending");
                assertThat(rows.getString(3)).isEqualTo("CANDIDATE");
                assertThat(rows.getBoolean(4)).isFalse();
                assertThat(rows.getBoolean(5)).isTrue();
                assertThat(rows.getString(6)).isEqualTo("RESTRICTED");
                assertThat(rows.getString(7)).isEqualTo("approved-policy");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("e-protected");
                assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.getString(8)).isEqualTo("true");
                assertThat(rows.getString(9)).isEqualTo("true");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(2)).isEqualTo(2);
                assertThat(rows.getString(8)).isEqualTo("false");
                assertThat(rows.getString(9)).isEqualTo("true");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static void verifyHostedFence(Connection owner, DataSource source, JdbcMemoryStore store) throws Exception {
        var host = new MemoryScopeVO("host", "tenant", MemoryScopeVO.RetentionScopeEnum.HOST, null, null, null, "opaque-trusted-host");
        var ordinary = permit(host, 1);
        try (var insert = owner.prepareStatement("""
                INSERT INTO egon_agent_memory.scope(scope_hash,canonical_scope,authority_epoch,storage_ref,state,
                    high_water_revision,created_at,updated_at)
                VALUES(?,?::jsonb,1,'LEGACY','FROZEN',9,clock_timestamp(),clock_timestamp())
                """)) {
            insert.setString(1, host.hash());
            insert.setString(2, new ObjectMapper().writeValueAsString(host.canonicalScope()));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        assertThatThrownBy(() -> store.inHostScope(source, host, ordinary, MemoryStore.READ_PURPOSE, false, ignored -> 1))
                .hasMessageContaining("MEMORY_AUTHORITY_REQUIRED");
        var typed = policy(host, new ReadPolicyVO(Set.of(), Set.of(), Set.of(), Set.of(), false, false), WriteMetadataVO.nativeDefaults());
        var observed = store.inHostScope(source, host, typed, MemoryStore.READ_PURPOSE, false, fence -> {
            assertThat(new JdbcTemplate(source).queryForObject("SELECT current_setting('transaction_isolation')", String.class))
                    .isEqualTo("read committed");
            return fence;
        });
        assertThat(observed).isEqualTo(new JdbcMemoryStore.HostFenceVO("LEGACY", 1, 9, true));
        assertThatThrownBy(() -> store.inHostScope(source, host, typed, MemoryStore.UPSERT_PURPOSE, true, ignored -> 1))
                .hasMessageContaining("MEMORY_UNAVAILABLE");
    }

    private static void verifyCatalogTamperFails(Connection owner, String runtime, DataSource source) throws SQLException {
        try (var statement = owner.createStatement()) {
            statement.execute("GRANT UPDATE (request_sha256) ON egon_agent_memory.receipt TO " + runtime);
            assertThatThrownBy(() -> new MemoryStoreStorageVerifier(source, SCHEMA).verify())
                    .hasMessage("MEMORY_STORAGE_UNAVAILABLE");
            statement.execute("REVOKE UPDATE (request_sha256) ON egon_agent_memory.receipt FROM " + runtime);
            statement.execute("ALTER INDEX egon_agent_memory.memory_fact_scope_type_id_idx RENAME TO invalid_fact_type_index");
            assertThatThrownBy(() -> new MemoryStoreStorageVerifier(source, SCHEMA).verify())
                    .hasMessage("MEMORY_STORAGE_UNAVAILABLE");
            statement.execute("ALTER INDEX egon_agent_memory.invalid_fact_type_index RENAME TO memory_fact_scope_type_id_idx");
            String original;
            try (var check = statement.executeQuery("""
                    SELECT pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE conrelid='egon_agent_memory.revision'::regclass AND conname='memory_revision_profile'
                    """)) {
                assertThat(check.next()).isTrue();
                original = check.getString(1);
            }
            statement.execute("ALTER TABLE egon_agent_memory.revision DROP CONSTRAINT memory_revision_profile");
            statement.execute("ALTER TABLE egon_agent_memory.revision ADD CONSTRAINT memory_revision_profile CHECK (true)");
            assertThatThrownBy(() -> new MemoryStoreStorageVerifier(source, SCHEMA).verify())
                    .hasMessage("MEMORY_STORAGE_UNAVAILABLE");
            statement.execute("ALTER TABLE egon_agent_memory.revision DROP CONSTRAINT memory_revision_profile");
            statement.execute("ALTER TABLE egon_agent_memory.revision ADD CONSTRAINT memory_revision_profile " + original);
            new MemoryStoreStorageVerifier(source, SCHEMA).verify();
        }
    }

    private static void verifySamePolicyBehavior(MemoryStore store, MemoryScopeVO scope) {
        Instant now = CLOCK.instant().truncatedTo(ChronoUnit.MICROS);
        var typed = new ReadPolicyVO(Set.of(MemoryFactTypeEnum.FACT), Set.of(), Set.of("INTERNAL", "RESTRICTED"),
                Set.of("default", "approved-policy"), true, false);
        var protectedWrite = policy(scope, typed, new WriteMetadataVO(true, null, null, "RESTRICTED", "approved-policy"));
        var unconfirmed = policy(scope, typed, new WriteMetadataVO(false, null, null, "RESTRICTED", "approved-policy"));
        var future = policy(scope, typed, new WriteMetadataVO(true, now.plusSeconds(300), null, "RESTRICTED", "approved-policy"));
        var expired = policy(scope, typed, new WriteMetadataVO(true, now.minusSeconds(10), now.minusSeconds(1), "RESTRICTED", "approved-policy"));
        var ordinary = permit(scope, 1);
        var plain = write("d-ordinary", "ordinary", 0, "plain entry");
        var protectedCommand = write("e-protected", "protected", 0, "private entry");

        store.upsert(write("a-pending", "pending", 0, "pending"), scope, unconfirmed);
        store.upsert(write("b-future", "future", 0, "after boundary"), scope, future);
        store.upsert(write("c-expired", "expired", 0, "before boundary"), scope, expired);
        store.upsert(plain, scope, ordinary);
        var protectedReceipt = store.upsert(protectedCommand, scope, protectedWrite);

        assertThat(store.retrieve(query(), scope, protectedWrite).getItems()).extracting(HitVO::factId)
                .containsExactly("d-ordinary", "e-protected");
        assertThat(store.retrieve(new MemoryQuery("private", 1, Set.of(MemoryFactTypeEnum.FACT)), scope, protectedWrite)
                .getItems()).extracting(HitVO::factId).containsExactly("e-protected");
        assertThat(store.retrieve(new MemoryQuery("private", 1, Set.of(MemoryFactTypeEnum.PREFERENCE)), scope, protectedWrite)
                .getItems()).isEmpty();
        assertThat(store.retrieve(query(), scope, ordinary).getItems()).extracting(HitVO::factId).containsExactly("d-ordinary");
        assertThatThrownBy(() -> store.upsert(protectedCommand, scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThat(store.upsert(protectedCommand, scope, protectedWrite)).isEqualTo(protectedReceipt);

        var downgrade = write("e-protected", "downgrade", 1, "now ordinary");
        var typedDefault = policy(scope, typed, WriteMetadataVO.nativeDefaults());
        var updated = store.upsert(downgrade, scope, typedDefault);
        assertThat(store.retrieve(query(), scope, ordinary).getItems()).extracting(HitVO::factId)
                .containsExactly("d-ordinary", "e-protected");
        assertThatThrownBy(() -> store.upsert(downgrade, scope, ordinary)).hasMessageContaining("NOT_FOUND");
        assertThat(store.upsert(downgrade, scope, typedDefault)).isEqualTo(updated);
    }

    private static CallPermitVO policy(MemoryScopeVO scope, ReadPolicyVO read, WriteMetadataVO write) {
        return new CallPermitVO("typed-permit", MemoryStore.CAPABILITY_REF, scope.hash(), 1, 1L, CLOCK.instant().plusSeconds(90),
                () -> false, new MemoryAuthority() {
                    @Override public boolean isCurrent(CallPermitVO permit, String purpose) { return true; }
                    @Override public ReadPolicyVO authorizeRead(MemoryScopeVO target, String purpose) { return read; }
                    @Override public WriteMetadataVO authorizeWrite(MemoryScopeVO target, MemoryWriteCommand command) { return write; }
                    @Override public void authorizeDelete(MemoryScopeVO target, MemoryDeleteCommand command) { }
                    @Override public ExtractionPolicyVO authorizeExtraction(MemoryScopeVO target, String ref, long version) {
                        return new ExtractionPolicyVO(Set.of());
                    }
                });
    }

    private static boolean compete(MemoryStore store, MemoryScopeVO scope, CallPermitVO permit, String id,
                                   CountDownLatch ready, CountDownLatch release) throws InterruptedException {
        ready.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture readiness timeout");
        try {
            return store.upsert(write("entry", id, 1, id), scope, permit).getRevision() == 2;
        } catch (top.egon.cola.component.common.core.exception.CommonException failure) {
            assertThat(failure).hasMessageContaining("VERSION_CONFLICT");
            return false;
        }
    }

    private static void assertDedicatedDatabase(Connection owner, Connection limited, String runtime) throws SQLException {
        try (var statement = owner.createStatement();
             var rows = statement.executeQuery("""
                     SELECT inet_server_addr()::text, current_database(), current_setting('server_encoding'),
                            to_regnamespace('egon_agent_memory') IS NULL, pg_is_in_recovery()
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("127.0.0.1");
            assertThat(rows.getString(2)).matches("tianji_it_[a-z0-9_]{1,40}");
            assertThat(rows.getString(3)).isEqualTo("UTF8");
            assertThat(rows.getBoolean(4)).isTrue();
            assertThat(rows.getBoolean(5)).isFalse();
        }
        try (var statement = limited.createStatement();
             var rows = statement.executeQuery("""
                     SELECT current_user, rolcanlogin, NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                            AND NOT rolreplication AND NOT rolbypassrls,
                            NOT has_database_privilege(current_user,current_database(),'CREATE'),
                            NOT has_schema_privilege(current_user,'public','CREATE'),
                            NOT EXISTS (SELECT FROM pg_auth_members WHERE member=oid)
                     FROM pg_roles WHERE rolname=current_user
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(runtime);
            for (int index = 2; index <= 6; index++) assertThat(rows.getBoolean(index)).isTrue();
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var stream = JdbcMemoryStorePostgresTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            assertThat(stream).as("Missing approved Agent Flow Memory artifact %s", name).isNotNull();
            return stream.readAllBytes();
        }
    }

    private static void assertManifest(byte[] script) throws Exception {
        var manifest = new ObjectMapper().readTree(resource("manifest.json"));
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.path("scripts").size()).isEqualTo(1);
        var entry = manifest.path("scripts").get(0);
        assertThat(entry.path("version").asInt()).isEqualTo(1);
        assertThat(entry.path("file").asText()).isEqualTo(MIGRATION);
        assertThat(entry.path("sha256").asText())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)));
    }

    private static void provision(Connection owner, String runtime, byte[] script) throws SQLException {
        owner.setAutoCommit(false);
        try (var statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute(new String(script, StandardCharsets.UTF_8));
            statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + runtime);
            statement.execute("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + runtime);
            statement.execute("GRANT UPDATE ON " + SCHEMA + ".scope, " + SCHEMA + ".fact TO " + runtime);
            owner.commit();
        } catch (SQLException failure) {
            owner.rollback();
            throw failure;
        } finally {
            owner.setAutoCommit(true);
        }
    }

    private static void assertCount(Connection limited, String table, long expected) throws SQLException {
        try (var statement = limited.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + table)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(expected);
        }
    }

    private static DataSource source(String url, String runtime) throws ReflectiveOperationException {
        Class<?> factory = Class.forName("org.springframework.jdbc.datasource.DriverManagerDataSource");
        return (DataSource) factory.getConstructor(String.class, String.class, String.class).newInstance(url, runtime, "");
    }

    private static void verifyStorage(DataSource source, String schema) throws ReflectiveOperationException {
        var verifier = Class.forName("top.egon.cola.component.agentflow.memory.jdbc.MemoryStoreStorageVerifier");
        var instance = verifier.getConstructor(DataSource.class, String.class).newInstance(source, schema);
        verifier.getMethod("verify").invoke(instance);
    }

    private static MemoryStore store(DataSource source, jakarta.validation.Validator validator)
            throws ReflectiveOperationException {
        var implementation = Class.forName("top.egon.cola.component.agentflow.memory.jdbc.JdbcMemoryStore");
        return (MemoryStore) implementation.getConstructor(DataSource.class, String.class, String.class, Clock.class,
                jakarta.validation.Validator.class).newInstance(source, SCHEMA, "COMPONENT", CLOCK, validator);
    }

    private static MemoryScopeVO scope(String tenant) {
        return new MemoryScopeVO("isolated-test", tenant, MemoryScopeVO.RetentionScopeEnum.INSTANCE, "principal", "instance", null, null);
    }

    private static CallPermitVO permit(MemoryScopeVO scope, long epoch) {
        return new CallPermitVO("test-permit", MemoryStore.CAPABILITY_REF, scope.hash(), 1, epoch, Instant.now().plusSeconds(90),
                () -> false, (permit, purpose) -> true);
    }

    private static MemoryWriteCommand write(String fact, String id, long expectedRevision, String text) {
        return new MemoryWriteCommand(fact, expectedRevision, id, MemoryFactTypeEnum.FACT, Map.of("text", text), List.of());
    }

    private static MemoryQuery query() {
        return new MemoryQuery(null, 10, Set.of());
    }
}
