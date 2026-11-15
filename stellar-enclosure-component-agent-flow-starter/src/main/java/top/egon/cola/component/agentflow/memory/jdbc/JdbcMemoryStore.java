package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;
import top.egon.cola.component.agentflow.api.memory.MemoryDeleteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryFactTypeEnum;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO;
import top.egon.cola.component.agentflow.api.memory.MemoryFactVO.ProvenanceVO;
import top.egon.cola.component.agentflow.api.memory.MemoryQuery;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult;
import top.egon.cola.component.agentflow.api.memory.MemoryRetrievalResult.HitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteCommand;
import top.egon.cola.component.agentflow.api.memory.MemoryWriteResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optional Memory store rooted in the explicitly selected DataSource. */
@Slf4j
@Validated
public class JdbcMemoryStore implements MemoryStore {

    public static final String COMPONENT = "COMPONENT";
    public static final String LEGACY = "LEGACY";
    private static final String ACTIVE = "ACTIVE";
    private static final String FROZEN = "FROZEN";
    private static final String NATIVE = "NATIVE";
    private static final String CANDIDATE = "CANDIDATE";
    private static final String DELETED = "DELETED";
    private static final String UPSERT = "UPSERT";
    private static final String DELETE = "DELETE";
    private static final long INITIAL_EPOCH = 1;
    private static final int PAGE_SIZE = 100;
    private static final int NATIVE_BYTES = 16 * 1024;
    private static final int PHYSICAL_BYTES = 128 * 1024;
    private static final Set<String> METADATA_FIELDS = Set.of("sensitivity", "retentionPolicyRef", "protectedMutation", "protectedReceipt");
    private static final Set<String> PROVENANCE_FIELDS = Set.of("sourceRef", "sourceRevision", "contentSha256", "observedAt");
    private static final WriteMetadataVO DEFAULTS = WriteMetadataVO.nativeDefaults();
    private static final String SCOPE_MATCH = "scope_hash=?::char(64) COLLATE \"C\"";
    private static final String HEAD_COLUMNS = """
            SELECT f.fact_id, f.fact_type, f.current_revision, f.updated_at, r.scope_revision, r.state, r.content_profile,
                   r.content, r.provenance, r.confirmed, r.effective_at, r.expires_at, r.native_metadata, r.content_sha256,
                   r.created_at AS revision_time
            """;
    private static final JsonMapper JSON = JsonMapper.builder(com.fasterxml.jackson.core.JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(PHYSICAL_BYTES)
                            .maxNumberLength(32).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final String scopeTable;
    private final String revisionTable;
    private final String factTable;
    private final String receiptTable;
    private final String storageRef;
    private final Clock clock;
    private final Validator validator;

    public JdbcMemoryStore(DataSource dataSource, String schema, String storageRef, Clock clock, Validator validator) {
        this.dataSource = Objects.requireNonNull(dataSource, "Memory data source");
        if (!COMPONENT.equals(storageRef)) throw new IllegalArgumentException("Invalid Agent Flow Memory storage reference");
        this.storageRef = storageRef;
        this.clock = Objects.requireNonNull(clock, "Memory clock");
        this.validator = Objects.requireNonNull(validator, "Memory validator");
        String namespace = MemoryStoreStorageVerifier.requireSchema(schema);
        scopeTable = namespace + ".scope";
        revisionTable = namespace + ".revision";
        factTable = namespace + ".fact";
        receiptTable = namespace + ".receipt";
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        named = new NamedParameterJdbcTemplate(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** The host must bind the same JDBC source and schema before sharing the scope transaction. */
    public boolean usesStorage(DataSource source, String schema) {
        return source == dataSource && schema != null && scopeTable.equals(schema + ".scope");
    }

    @Override
    public MemoryRetrievalResult retrieve(MemoryQuery query, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(query, "query").snapshot();
        validate(fixed);
        boolean forModel = MODEL_CAPABILITY_REF.equals(Objects.requireNonNull(permit, "permit").capabilityRef());
        String capability = forModel ? MODEL_CAPABILITY_REF : CAPABILITY_REF;
        String purpose = forModel ? MODEL_READ_PURPOSE : READ_PURPOSE;
        var access = nativeAccess(scope, permit, capability, purpose);
        return inTransaction(() -> {
            var row = selectScope(scope, false);
            if (row != null) requireNativeRow(row, access);
            var policy = readPolicy(access, forModel);
            Instant observed = now();
            List<HitVO> items = row == null ? List.of() : retrieveEligible(fixed, access, row, policy, observed);
            access.current();
            return new MemoryRetrievalResult(items);
        });
    }

    @Override
    public MemoryWriteResult upsert(MemoryWriteCommand command, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(command, "command").snapshot();
        validate(fixed);
        var access = nativeAccess(scope, permit, CAPABILITY_REF, UPSERT_PURPOSE);
        return inTransaction(() -> {
            ScopeRowVO row = selectScope(scope, true);
            WriteMetadataVO approved;
            if (row == null) {
                // Check first-write permission before creating any scope fact.
                approved = writeMetadata(access, fixed);
                if (fixed.getExpectedRevision() != 0) throw Access.denied("VERSION_CONFLICT");
                createNativeScope(scope, now());
                row = selectScope(scope, true);
                requireNativeRow(row, access);
            } else {
                requireNativeRow(row, access);
                approved = writeMetadata(access, fixed);
            }
            String fingerprint = NativeCodec.upsertFingerprint(access.hash(), fixed, approved);
            var original = replay(access, fixed.getCommandId(), UPSERT, fingerprint);
            if (original != null) return original;
            var prior = currentHead(access.hash(), fixed.getFactId());
            requireMutationVisibility(prior, access);
            long priorRevision = prior == null ? 0 : prior.fact().revision();
            if (priorRevision != fixed.getExpectedRevision()) throw Access.denied("VERSION_CONFLICT");
            if (prior != null && prior.fact().type() != fixed.getType()) throw Access.denied("TYPE_CONFLICT");
            if (prior != null && prior.fact().tombstone()) throw Access.denied("NOT_FOUND");

            Instant committed = commitTime(row);
            var fact = new MemoryFactVO(fixed.getFactId(), fixed.getType(), nextRevision(priorRevision), fixed.getContent(),
                    fixed.getProvenance(), false);
            boolean protectedMutation = !DEFAULTS.equals(approved);
            boolean protectedReceipt = protectedMutation || prior != null && prior.protectedMutation();
            return append(access, row, prior, new RevisionVO(fact, approved.effectiveAtCommit(committed), protectedMutation,
                    protectedReceipt, committed), fixed.getCommandId(), UPSERT, fingerprint);
        });
    }

    @Override
    public MemoryWriteResult delete(MemoryDeleteCommand command, MemoryScopeVO scope, CallPermitVO permit) {
        var fixed = Objects.requireNonNull(command, "command").snapshot();
        validate(fixed);
        var access = nativeAccess(scope, permit, CAPABILITY_REF, DELETE_PURPOSE);
        return inTransaction(() -> {
            ScopeRowVO row = selectScope(scope, true);
            if (row != null) requireNativeRow(row, access);
            if (access.authority() != null) {
                access.authority().authorizeDelete(scope, fixed);
                access.current();
            }
            if (row == null) throw Access.denied("NOT_FOUND");
            String fingerprint = NativeCodec.deleteFingerprint(access.hash(), fixed);
            var original = replay(access, fixed.getCommandId(), DELETE, fingerprint);
            if (original != null) return original;
            var prior = currentHead(access.hash(), fixed.getFactId());
            requireMutationVisibility(prior, access);
            if (prior == null || prior.fact().tombstone()) throw Access.denied("NOT_FOUND");
            if (prior.fact().revision() != fixed.getExpectedRevision()) throw Access.denied("VERSION_CONFLICT");

            var fact = new MemoryFactVO(fixed.getFactId(), prior.fact().type(), nextRevision(prior.fact().revision()), null, null, true);
            return append(access, row, prior, new RevisionVO(fact, prior.metadata(), prior.protectedMutation(),
                    prior.protectedMutation(), commitTime(row)), fixed.getCommandId(), DELETE, fingerprint);
        });
    }

    private List<HitVO> retrieveEligible(MemoryQuery query, NativeAccessVO access, ScopeRowVO scopeRow, ReadPolicyVO policy,
                                         Instant observed) {
        Set<MemoryFactTypeEnum> types = policy == null ? Set.of(MemoryFactTypeEnum.values()) : policy.types();
        var selected = types.stream().filter(type -> query.getTypes().isEmpty() || query.getTypes().contains(type))
                .map(MemoryFactTypeEnum::code).toList();
        var sensitivities = policy == null ? Set.of(DEFAULTS.sensitivity()) : policy.sensitivities();
        var retention = policy == null ? Set.of(DEFAULTS.retentionPolicyRef()) : policy.retentionPolicyRefs();
        if (selected.isEmpty() || sensitivities.isEmpty() || retention.isEmpty()) return List.of();

        var collected = new ArrayList<HitVO>(query.getLimit());
        String after = "";
        while (collected.size() < query.getLimit()) {
            var page = visiblePage(access.hash(), selected, sensitivities, retention, observed, after);
            if (page.isEmpty()) break;
            for (var candidate : page) {
                var fact = candidate.fact();
                if (candidate.scopeRevision() > scopeRow.highWaterRevision()) throw unavailable();
                if (policy != null && !policy.allows(access.scope(), access.purpose(), fact, candidate.metadata())) continue;
                if (query.getText() != null && !query.getText().isBlank() && !containsText(fact.content(), query.getText())) continue;
                collected.add(new HitVO(fact.factId(), fact.type(), fact.revision(), fact.content(), fact.provenance(), null, observed));
                if (collected.size() == query.getLimit()) break;
            }
            access.current();
            if (page.size() < PAGE_SIZE) break;
            after = page.getLast().fact().factId();
        }
        return List.copyOf(collected);
    }

    private List<StoredHeadVO> visiblePage(String hash, List<String> types, Set<String> sensitivities, Set<String> retention,
                                           Instant observed, String after) {
        String sql = HEAD_COLUMNS + joinHead() + """
                WHERE f.scope_hash=CAST(:scope AS char(64)) COLLATE "C" AND f.fact_type IN (:types)
                  AND r.state='ACTIVE' AND r.confirmed AND r.content_profile='NATIVE'
                  AND r.effective_at<=:observed AND (r.expires_at IS NULL OR r.expires_at>:observed)
                  AND (r.native_metadata->>'sensitivity') COLLATE "C" IN (:sensitivities)
                  AND (r.native_metadata->>'retentionPolicyRef') COLLATE "C" IN (:retention)
                  AND f.fact_id>:after
                ORDER BY f.fact_id LIMIT :pageSize
                """;
        return named.query(sql, new MapSqlParameterSource().addValue("scope", hash).addValue("types", types)
                .addValue("observed", stamp(observed)).addValue("sensitivities", sensitivities).addValue("retention", retention)
                .addValue("after", after).addValue("pageSize", PAGE_SIZE), (rs, index) -> head(rs));
    }

    private StoredHeadVO currentHead(String hash, String factId) {
        var rows = jdbc.query(HEAD_COLUMNS + joinHead() + "WHERE f." + SCOPE_MATCH + " AND f.fact_id=?",
                (rs, index) -> head(rs), hash, factId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String joinHead() {
        return "FROM " + factTable + " f JOIN " + revisionTable
                + " r ON r.scope_hash=f.scope_hash AND r.fact_id=f.fact_id AND r.revision=f.current_revision ";
    }

    private MemoryWriteResult replay(NativeAccessVO access, String commandId, String operation, String fingerprint) {
        var rows = jdbc.query("""
                SELECT c.request_sha256, c.operation, c.fact_id, c.result_revision, c.created_at AS receipt_time,
                       r.created_at AS revision_time, r.content_profile, r.content_sha256, r.native_metadata,
                       r.confirmed, r.effective_at, r.expires_at, r.state, c.authority_epoch
                FROM %s c JOIN %s r ON r.scope_hash=c.scope_hash AND r.fact_id=c.fact_id AND r.revision=c.result_revision
                WHERE c.%s AND c.command_namespace='NATIVE' AND c.command_id=?
                """.formatted(receiptTable, revisionTable, SCOPE_MATCH), (rs, index) -> {
            if (!NATIVE.equals(rs.getString("content_profile")) || rs.getLong("authority_epoch") < 1) throw unavailable();
            var metadata = metadata(rs);
            if (metadata.protectedReceipt() && access.authority() == null) throw Access.denied("NOT_FOUND");
            if (!fingerprint.equals(rs.getString("request_sha256")) || !operation.equals(rs.getString("operation"))) {
                throw Access.denied("COMMAND_CONFLICT");
            }
            Instant at = instant(rs, "receipt_time");
            String state = rs.getString("state");
            String hash = rs.getString("content_sha256");
            if (!at.equals(instant(rs, "revision_time")) || !hash.matches("[0-9a-f]{64}")
                    || DELETE.equals(operation) && (!DELETED.equals(state) || !NativeCodec.contentSha256(null).equals(hash))
                    || UPSERT.equals(operation) && !Set.of(ACTIVE, CANDIDATE).contains(state)) throw unavailable();
            return new MemoryWriteResult(rs.getString("fact_id"), rs.getLong("result_revision"), commandId, hash, at);
        }, access.hash(), commandId);
        access.current();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private MemoryWriteResult append(NativeAccessVO access, ScopeRowVO row, StoredHeadVO previous, RevisionVO write,
                                     String commandId, String operation, String fingerprint) {
        if (previous != null && previous.scopeRevision() > row.highWaterRevision()) throw unavailable();
        var fact = write.fact();
        var metadata = write.metadata();
        long watermark = nextRevision(row.highWaterRevision());
        String contentHash = NativeCodec.contentSha256(fact.content());
        var stamp = stamp(write.committedAt());
        String state = fact.tombstone() ? DELETED : metadata.confirmed() ? ACTIVE : CANDIDATE;
        access.current();
        requireOne(jdbc.update("""
                INSERT INTO %s(scope_hash,fact_id,revision,scope_revision,content_profile,content,provenance,state,confirmed,
                               effective_at,expires_at,redacted_at,host_metadata,native_metadata,content_sha256,created_at)
                VALUES (?,?,?,?,'NATIVE',?::jsonb,?::jsonb,?,?,?, ?,NULL,NULL,?::jsonb,?,?)
                """.formatted(revisionTable), access.hash(), fact.factId(), fact.revision(), watermark,
                fact.tombstone() ? null : json(fact.content()), fact.tombstone() ? null : encodeProvenance(fact.provenance()), state,
                metadata.confirmed(), stamp(metadata.effectiveAt()), stampNullable(metadata.expiresAt()),
                encodeMetadata(write), contentHash, stamp));
        if (previous == null) {
            requireOne(jdbc.update("""
                    INSERT INTO %s(scope_hash,fact_id,fact_type,created_at,current_revision,updated_at) VALUES (?,?,?,?,?,?)
                    """.formatted(factTable), access.hash(), fact.factId(), fact.type().code(), stamp, fact.revision(), stamp));
        } else {
            requireOne(jdbc.update("""
                    UPDATE %s SET current_revision=?,updated_at=? WHERE %s AND fact_id=? AND fact_type=? AND current_revision=?
                    """.formatted(factTable, SCOPE_MATCH), fact.revision(), stamp, access.hash(), fact.factId(), fact.type().code(),
                    previous.fact().revision()));
        }
        requireOne(jdbc.update("""
                UPDATE %s SET high_water_revision=?,updated_at=? WHERE %s AND authority_epoch=? AND storage_ref=?
                           AND state='ACTIVE' AND high_water_revision=?
                """.formatted(scopeTable, SCOPE_MATCH), watermark, stamp, access.hash(), row.epoch(), storageRef, row.highWaterRevision()));
        requireOne(jdbc.update("""
                INSERT INTO %s(scope_hash,command_namespace,command_id,request_sha256,operation,fact_id,result_revision,authority_epoch,created_at)
                VALUES (?,'NATIVE',?,?,?,?,?,?,?)
                """.formatted(receiptTable), access.hash(), commandId, fingerprint, operation, fact.factId(), fact.revision(),
                row.epoch(), stamp));
        access.current();
        return new MemoryWriteResult(fact.factId(), fact.revision(), commandId, contentHash, write.committedAt());
    }

    private static StoredHeadVO head(ResultSet rs) throws SQLException {
        if (!NATIVE.equals(rs.getString("content_profile"))) throw unavailable();
        try {
            var evidence = metadata(rs);
            Instant updated = instant(rs, "updated_at");
            if (!updated.equals(instant(rs, "revision_time"))) throw unavailable();
            var type = MemoryFactTypeEnum.fromCode(rs.getString("fact_type"));
            var hash = rs.getString("content_sha256");
            var state = rs.getString("state");
            var content = rs.getString("content");
            var sources = rs.getString("provenance");
            boolean tombstone = DELETED.equals(state);
            if (tombstone ? content != null || sources != null
                    : !Set.of(ACTIVE, CANDIDATE).contains(state) || ACTIVE.equals(state) != evidence.value().confirmed()
                    || content == null || sources == null) throw unavailable();
            Map<String, Object> body = tombstone ? null : JsonValues.copyObject(JSON.readValue(content, JSON_OBJECT), NATIVE_BYTES);
            var provenance = tombstone ? null : decodeProvenance(sources);
            var fact = new MemoryFactVO(rs.getString("fact_id"), type, rs.getLong("current_revision"), body, provenance, tombstone);
            if (!NativeCodec.contentSha256(body).equals(hash)) throw unavailable();
            long scopeRevision = rs.getLong("scope_revision");
            if (scopeRevision < 1) throw unavailable();
            return new StoredHeadVO(fact, evidence.value(), evidence.protectedMutation(), scopeRevision);
        } catch (JsonProcessingException | IllegalArgumentException | DateTimeParseException malformed) {
            throw unavailable();
        }
    }

    private static DecodedMetadataVO metadata(ResultSet rs) throws SQLException {
        try {
            var value = boundedTree(rs.getString("native_metadata"), 4096);
            if (!exact(value, METADATA_FIELDS) || !value.path("protectedMutation").isBoolean() || !value.path("protectedReceipt").isBoolean()) {
                throw unavailable();
            }
            var policy = new WriteMetadataVO(rs.getBoolean("confirmed"), instant(rs, "effective_at"), optionalInstant(rs, "expires_at"),
                    text(value, "sensitivity"), text(value, "retentionPolicyRef"));
            boolean protectedMutation = value.path("protectedMutation").booleanValue();
            boolean protectedReceipt = value.path("protectedReceipt").booleanValue();
            if (protectedMutation && !protectedReceipt || !protectedMutation
                    && (!policy.confirmed() || policy.expiresAt() != null || !DEFAULTS.sensitivity().equals(policy.sensitivity())
                    || !DEFAULTS.retentionPolicyRef().equals(policy.retentionPolicyRef()))) throw unavailable();
            return new DecodedMetadataVO(policy, protectedMutation, protectedReceipt);
        } catch (IllegalArgumentException malformed) {
            throw unavailable();
        }
    }

    private static String encodeMetadata(RevisionVO revision) {
        return json(Map.of("sensitivity", revision.metadata().sensitivity(),
                "retentionPolicyRef", revision.metadata().retentionPolicyRef(), "protectedMutation", revision.protectedMutation(),
                "protectedReceipt", revision.protectedReceipt()));
    }

    private static List<ProvenanceVO> decodeProvenance(String payload) {
        var root = boundedTree(payload, PHYSICAL_BYTES);
        if (!root.isArray() || root.size() > 32) throw unavailable();
        var decoded = new ArrayList<ProvenanceVO>(root.size());
        for (JsonNode value : root) {
            var revision = value.path("sourceRevision");
            if (!exact(value, PROVENANCE_FIELDS) || !revision.isIntegralNumber() || !revision.canConvertToLong()) {
                throw unavailable();
            }
            Instant time = Instant.parse(text(value, "observedAt"));
            if (!time.toString().equals(text(value, "observedAt"))) throw unavailable();
            decoded.add(new ProvenanceVO(text(value, "sourceRef"), revision.longValue(), text(value, "contentSha256"), time));
        }
        if (encodeProvenance(decoded).getBytes(StandardCharsets.UTF_8).length > NATIVE_BYTES) throw unavailable();
        return List.copyOf(decoded);
    }

    private static String encodeProvenance(List<ProvenanceVO> provenance) {
        var values = provenance.stream().map(source -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contentSha256", source.contentSha256());
            entry.put("observedAt", source.observedAt().toString());
            entry.put("sourceRef", source.sourceRef());
            entry.put("sourceRevision", source.sourceRevision());
            return entry;
        }).toList();
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException malformed) {
            throw unavailable();
        }
    }

    private static JsonNode boundedTree(String payload, int bytes) {
        if (payload == null || payload.length() > bytes || payload.getBytes(StandardCharsets.UTF_8).length > bytes) throw unavailable();
        try {
            return Objects.requireNonNull(JSON.readTree(payload));
        } catch (JsonProcessingException malformed) {
            throw unavailable();
        }
    }

    private static boolean exact(JsonNode node, Set<String> fields) {
        return node.isObject() && node.size() == fields.size() && fields.stream().allMatch(node::has);
    }

    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual()) throw unavailable();
        return value.textValue();
    }

    private static String json(Map<String, ?> value) {
        return new String(JsonValues.canonicalBytes(value), StandardCharsets.UTF_8);
    }

    private NativeAccessVO nativeAccess(MemoryScopeVO scope, CallPermitVO permit, String capability, String purpose) {
        validate(scope);
        validate(permit);
        var access = new NativeAccessVO(scope, scope.hash(), permit, capability, purpose, clock);
        access.current();
        if (scope.retentionScope() == MemoryScopeVO.RetentionScopeEnum.HOST) {
            Access.requireAuthority(permit);
            throw unavailable();
        }
        if (!Objects.equals(permit.authorityEpoch(), INITIAL_EPOCH)) throw Access.denied("MEMORY_AUTHORITY_EXPIRED");
        return access;
    }

    private void requireNativeRow(ScopeRowVO row, NativeAccessVO access) {
        if (row == null || !storageRef.equals(row.storageRef()) || !ACTIVE.equals(row.state())) throw unavailable();
        if (!Objects.equals(access.permit().authorityEpoch(), row.epoch())) throw Access.denied("MEMORY_AUTHORITY_EXPIRED");
        access.current();
    }

    private static ReadPolicyVO readPolicy(NativeAccessVO access, boolean forModel) {
        access.current();
        var authority = access.authority();
        if (authority == null) {
            if (forModel) throw Access.denied("MEMORY_AUTHORITY_REQUIRED");
            return null;
        }
        var policy = authority.authorizeRead(access.scope(), access.purpose());
        if (policy == null || forModel && !policy.modelEgress()) {
            throw Access.denied(forModel ? "MEMORY_EGRESS_DENIED" : "MEMORY_READ_DENIED");
        }
        access.current();
        return policy;
    }

    private static WriteMetadataVO writeMetadata(NativeAccessVO access, MemoryWriteCommand command) {
        var authority = access.authority();
        if (authority == null) return DEFAULTS;
        var approved = authority.authorizeWrite(access.scope(), command.snapshot());
        if (approved == null) throw Access.denied("MEMORY_WRITE_DENIED");
        access.current();
        return approved;
    }

    private static void requireMutationVisibility(StoredHeadVO prior, NativeAccessVO access) {
        if (prior != null && prior.protectedMutation() && access.authority() == null) throw Access.denied("NOT_FOUND");
    }

    private static boolean containsText(Object value, String query) {
        if (value instanceof String text) return text.contains(query);
        if (value instanceof Map<?, ?> object) return object.values().stream().anyMatch(member -> containsText(member, query));
        return value instanceof List<?> list && list.stream().anyMatch(member -> containsText(member, query));
    }

    private Instant commitTime(ScopeRowVO row) {
        Instant time = now();
        if (time.isBefore(row.updatedAt())) throw unavailable();
        return time;
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) throw Access.denied("VERSION_CONFLICT");
        return revision + 1;
    }

    private static void requireOne(int written) {
        if (written != 1) throw unavailable();
    }

    /**
     * Keeps one exact existing HOST scope fenced while the registered host performs local SQL
     * through the same DataSource and transaction. Host code owns LEGACY creation and migration.
     */
    public <T> T inHostScope(@NotNull DataSource hostDataSource, @NotNull @Valid MemoryScopeVO scope,
                              @NotNull @Valid CallPermitVO permit, @NotNull String purpose, boolean write,
                              @NotNull Function<HostFenceVO, T> command) {
        if (hostDataSource != dataSource) throw unavailable();
        if (scope == null || scope.retentionScope() != MemoryScopeVO.RetentionScopeEnum.HOST) {
            throw Access.denied("MEMORY_SCOPE_MISMATCH");
        }
        validate(scope);
        validate(permit);
        Access.requireAuthority(permit);
        Access.requireCurrent(permit, scope.hash(), CAPABILITY_REF, purpose, clock);
        return inTransaction(() -> {
            ScopeRowVO row = selectScope(scope, write);
            if (row == null || write && !ACTIVE.equals(row.state())) throw unavailable();
            requireCurrent(scope, permit, CAPABILITY_REF, purpose, row.epoch());
            T result = command.apply(new HostFenceVO(row.storageRef(), row.epoch(), row.highWaterRevision(), FROZEN.equals(row.state())));
            requireCurrent(scope, permit, CAPABILITY_REF, purpose, row.epoch());
            return result;
        });
    }

    private void createNativeScope(MemoryScopeVO scope, Instant time) {
        String insert = """
                INSERT INTO %s(scope_hash,canonical_scope,authority_epoch,storage_ref,state,high_water_revision,created_at,updated_at)
                VALUES(?,?::jsonb,?,?,'ACTIVE',0,?,?) ON CONFLICT (scope_hash) DO NOTHING
                """.formatted(scopeTable);
        jdbc.update(insert, scope.hash(), nativeScope(scope), INITIAL_EPOCH, storageRef, stamp(time), stamp(time));
    }

    private ScopeRowVO selectScope(MemoryScopeVO scope, boolean write) {
        String sql = """
                SELECT canonical_scope = ?::jsonb AS exact_scope, authority_epoch, storage_ref, state, high_water_revision, updated_at
                FROM %s WHERE %s %s
                """.formatted(scopeTable, SCOPE_MATCH, write ? "FOR UPDATE" : "FOR SHARE");
        var rows = jdbc.query(sql, (rs, row) -> {
            if (!rs.getBoolean("exact_scope")) throw Access.denied("MEMORY_SCOPE_MISMATCH");
            String route = rs.getString("storage_ref");
            String state = rs.getString("state");
            long epoch = rs.getLong("authority_epoch");
            long watermark = rs.getLong("high_water_revision");
            if ((!COMPONENT.equals(route) && !LEGACY.equals(route)) || (!ACTIVE.equals(state) && !FROZEN.equals(state))
                    || epoch <= 0 || watermark < 0) throw unavailable();
            return new ScopeRowVO(epoch, route, state, watermark, rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        }, nativeScope(scope), scope.hash());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireCurrent(MemoryScopeVO scope, CallPermitVO permit, String capability, String purpose, long epoch) {
        Access.requireCurrent(permit, scope.hash(), capability, purpose, clock);
        if (!Objects.equals(permit.authorityEpoch(), epoch)) throw Access.denied("MEMORY_AUTHORITY_EXPIRED");
    }

    private static String nativeScope(MemoryScopeVO scope) {
        byte[] canonical = JsonValues.canonicalBytes(scope.canonicalScope());
        if (canonical.length > 4096) throw Access.denied("MEMORY_SCOPE_MISMATCH");
        return new String(canonical, StandardCharsets.UTF_8);
    }

    private Instant now() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_catalog.clock_timestamp()::timestamptz(6)",
                OffsetDateTime.class)).toInstant();
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataAccessException | TransactionException database) {
            log.debug("Agent Flow Memory database unavailable");
            throw unavailable();
        }
    }

    private <T> void validate(T input) {
        var violations = validator.validate(Objects.requireNonNull(input, "Memory boundary"));
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }

    private static OffsetDateTime stamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime stampNullable(Instant instant) {
        return instant == null ? null : stamp(instant);
    }

    private static Instant optionalInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var result = optionalInstant(rs, column);
        if (result == null || result.getNano() % 1_000 != 0) throw unavailable();
        return result;
    }

    private static RuntimeException unavailable() {
        return Access.denied("MEMORY_UNAVAILABLE");
    }

    public record HostFenceVO(String storageRef, long authorityEpoch, long highWaterRevision, boolean frozen) { }

    private record ScopeRowVO(long epoch, String storageRef, String state, long highWaterRevision, Instant updatedAt) { }

    private record NativeAccessVO(MemoryScopeVO scope, String hash, CallPermitVO permit, String capability, String purpose, Clock clock) {
        MemoryAuthority authority() {
            return permit.recheck() instanceof MemoryAuthority authority ? authority : null;
        }

        void current() {
            Access.requireCurrent(permit, hash, capability, purpose, clock);
        }
    }

    private record RevisionVO(MemoryFactVO fact, WriteMetadataVO metadata, boolean protectedMutation, boolean protectedReceipt,
                              Instant committedAt) { }

    private record StoredHeadVO(MemoryFactVO fact, WriteMetadataVO metadata, boolean protectedMutation, long scopeRevision) { }

    private record DecodedMetadataVO(WriteMetadataVO value, boolean protectedMutation, boolean protectedReceipt) { }
}
