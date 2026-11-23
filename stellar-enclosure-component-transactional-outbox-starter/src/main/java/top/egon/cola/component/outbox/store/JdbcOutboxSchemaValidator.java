package top.egon.cola.component.outbox.store;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import top.egon.cola.component.common.core.validation.BaseValidator;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.persistence.OutboxShardRouting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only check of the deployed V1 contract; it never creates or repairs schema objects. */
public final class JdbcOutboxSchemaValidator extends BaseValidator {
    private static final String TABLE = "egon_cola_outbox_message";
    private static final Map<String, Column> REQUIRED = columns();
    private final JdbcTemplate jdbc;
    private final String schema;
    private final ValidationUtils validationUtils;

    public JdbcOutboxSchemaValidator(JdbcTemplate jdbc, String schema, ValidationUtils validationUtils) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.validationUtils = Objects.requireNonNull(validationUtils, "validationUtils");
    }

    @Override
    protected ValidationUtils getValidationUtils() {
        return validationUtils;
    }

    public void validate() {
        try {
            String product = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            if (!"PostgreSQL".equals(product)) throw invalid();
            List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name,data_type,is_nullable,character_maximum_length "
                    + "FROM information_schema.columns WHERE table_schema=? AND table_name=?", schema, TABLE);
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            for (Map<String, Object> column : columns) byName.put((String) column.get("column_name"), column);
            for (var expected : REQUIRED.entrySet()) {
                Map<String, Object> actual = byName.get(expected.getKey());
                if (actual == null || !expected.getValue().type().equals(actual.get("data_type"))
                        || !(expected.getValue().nullable() ? "YES" : "NO").equals(actual.get("is_nullable"))) throw invalid();
                Object length = actual.get("character_maximum_length");
                if (expected.getValue().minimumLength() > 0
                        && (!(length instanceof Number number) || number.intValue() < expected.getValue().minimumLength())) {
                    throw invalid();
                }
            }
            String sequence = jdbc.queryForObject("SELECT pg_get_serial_sequence(?, 'id')", String.class,
                    '"' + schema + "\".\"" + TABLE + '"');
            if (sequence == null) throw invalid();
            List<Map<String, Object>> unique = jdbc.queryForList("""
                    SELECT a.attname AS column_name, pg_get_expr(i.indpred,i.indrelid) AS predicate,
                           i.indisprimary AS primary_key
                    FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class t ON t.oid=i.indrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                    JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=i.indkey[0]
                    WHERE n.nspname=? AND t.relname=? AND i.indisunique AND i.indisvalid AND i.indisready
                      AND i.indnkeyatts=1
                    """, schema, TABLE);
            boolean message = unique.stream().anyMatch(index -> "message_id".equals(index.get("column_name"))
                    && index.get("predicate") == null);
            boolean idempotency = unique.stream().anyMatch(index -> "idempotency_key".equals(index.get("column_name"))
                    && (index.get("predicate") == null || "(idempotency_key IS NOT NULL)".equals(index.get("predicate"))));
            boolean primary = unique.stream().anyMatch(index -> "id".equals(index.get("column_name"))
                    && Boolean.TRUE.equals(index.get("primary_key")) && index.get("predicate") == null);
            if (!primary || !message || !idempotency) throw invalid();
            List<Map<String, Object>> indexes = jdbc.queryForList("""
                    SELECT idx.relname, pg_get_indexdef(i.indexrelid) AS definition,
                           pg_get_expr(i.indpred,i.indrelid) AS predicate
                    FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class t ON t.oid=i.indrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                    JOIN pg_catalog.pg_class idx ON idx.oid=i.indexrelid
                    WHERE n.nspname=? AND t.relname=? AND i.indisvalid AND i.indisready
                    """, schema, TABLE);
            if (!indexMatches(indexes, "idx_outbox_claim", "next_attempt_at, id", Set.of("PENDING", "RETRY_WAIT"))
                    || !indexMatches(indexes, "idx_outbox_reclaim", "locked_until, id", Set.of("PROCESSING"))
                    || !indexMatches(indexes, "idx_outbox_cleanup", "completed_at, id", Set.of("SUCCEEDED"))) throw invalid();
            List<Map<String, Object>> checks = jdbc.queryForList("""
                    SELECT c.conname, pg_get_constraintdef(c.oid) AS definition FROM pg_catalog.pg_constraint c
                    JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname=? AND t.relname=? AND c.contype='c' AND c.convalidated
                    """, schema, TABLE);
            Map<String, String> definitions = new LinkedHashMap<>();
            for (Map<String, Object> check : checks) {
                definitions.put((String) check.get("conname"), (String) check.get("definition"));
            }
            String status = definitions.get("ck_outbox_status");
            if (status == null || !status.contains("status") || !status.contains("ANY") || status.contains(" OR ")) throw invalid();
            var values = new java.util.HashSet<String>();
            var matches = java.util.regex.Pattern.compile("'([^']*)'").matcher(status);
            while (matches.find()) values.add(matches.group(1));
            if (!values.equals(Set.of("PENDING", "PROCESSING", "RETRY_WAIT", "SUCCEEDED", "DEAD"))
                    || !constraintContains(definitions.get("ck_outbox_attempt_count"), "attempt_count >= 0")
                    || !constraintContains(definitions.get("ck_outbox_max_attempts"), "max_attempts >= 1")) throw invalid();
        } catch (DataAccessException failure) {
            throw new OutboxConfigurationException("JDBC outbox schema validation failed", failure);
        }
    }

    /** Checks the V2 technical tables without running or repairing DDL. */
    public void validateCoordination() {
        try {
            validateQueueTable(OutboxShardRouting.QUEUE_MEMBER_TABLE, "member", "instance", "instance_id", Map.of(
                    "instance_id", "character varying", "capabilities_json", "text",
                    "last_seen_at", "timestamp with time zone"));
            validateQueueTable(OutboxShardRouting.QUEUE_LEASE_TABLE, "lease", "channel", "channel", Map.of(
                    "channel", "character varying", "owner_instance_id", "character varying", "epoch", "bigint"));
        } catch (DataAccessException failure) {
            throw new OutboxConfigurationException("JDBC outbox coordination schema validation failed", failure);
        }
    }

    private void validateQueueTable(String table, String type, String suffix, String key, Map<String, String> specific) {
        Map<String, String> columns = new LinkedHashMap<>(specific);
        columns.put("id", "bigint");
        columns.put("shard_slot", "integer");
        columns.put("expires_at", "timestamp with time zone");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, character_maximum_length, datetime_precision,
                       is_identity
                FROM information_schema.columns WHERE table_schema=? AND table_name=?
                """, schema, table);
        if (rows.size() != columns.size() || rows.stream().anyMatch(row -> !queueColumnMatches(columns, row))) {
            throw invalidCoordination(table);
        }
        Map<String, String> constraints = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                WHERE n.nspname=? AND t.relname=? AND c.convalidated
                """, schema, table).forEach(row -> constraints.put((String) row.get("conname"),
                        (String) row.get("definition")));
        if (!"PRIMARY KEY (id)".equals(constraints.get("pk_outbox_queue_" + type))
                || !("UNIQUE (shard_slot, " + key + ")").equals(
                        constraints.get("uk_outbox_queue_" + type + "_slot_" + suffix))
                || !constraintContains(constraints.get(table + "_shard_slot_check"), "shard_slot = 0")) {
            throw invalidCoordination(table);
        }
        if ("lease".equals(type)
                ? !constraintContains(constraints.get(table + "_epoch_check"), "epoch > 0")
                : !constraintContains(constraints.get("ck_outbox_queue_member_expiry"), "expires_at >= last_seen_at")) {
            throw invalidCoordination(table);
        }
    }

    private static boolean queueColumnMatches(Map<String, String> expected, Map<String, Object> row) {
        String column = (String) row.get("column_name");
        String type = expected.get(column);
        if (type == null || !type.equals(row.get("data_type")) || !"NO".equals(row.get("is_nullable"))) {
            return false;
        }
        return switch (type) {
            case "timestamp with time zone" -> row.get("datetime_precision") instanceof Number n && n.intValue() == 6;
            case "character varying" -> row.get("character_maximum_length") instanceof Number n
                    && n.intValue() == ("channel".equals(column) ? 64 : 128);
            case "bigint" -> !"id".equals(column) || "YES".equals(row.get("is_identity"));
            default -> true;
        };
    }

    private static OutboxConfigurationException invalidCoordination(String table) {
        return new OutboxConfigurationException("JDBC outbox schema does not satisfy the deployed V2 contract: " + table);
    }

    private static Map<String, Column> columns() {
        Map<String, Column> result = new LinkedHashMap<>();
        result.put("id", new Column("bigint", false, 0));
        result.put("message_id", new Column("character varying", false, 64));
        result.put("idempotency_key", new Column("character varying", true, 256));
        result.put("message_fingerprint", new Column("character", false, 64));
        result.put("channel", new Column("character varying", false, 64));
        result.put("destination", new Column("character varying", false, 256));
        result.put("payload", new Column("text", false, 0));
        result.put("content_type", new Column("character varying", false, 128));
        result.put("schema_version", new Column("character varying", true, 32));
        result.put("headers_json", new Column("text", false, 0));
        result.put("trace_id", new Column("character varying", true, 128));
        result.put("status", new Column("character varying", false, 32));
        result.put("attempt_count", new Column("integer", false, 0));
        result.put("max_attempts", new Column("integer", false, 0));
        result.put("next_attempt_at", new Column("timestamp with time zone", false, 0));
        result.put("locked_by", new Column("character varying", true, 128));
        result.put("locked_until", new Column("timestamp with time zone", true, 0));
        result.put("last_error_code", new Column("character varying", true, 64));
        result.put("last_error_message", new Column("text", true, 0));
        result.put("created_at", new Column("timestamp with time zone", false, 0));
        result.put("updated_at", new Column("timestamp with time zone", false, 0));
        result.put("completed_at", new Column("timestamp with time zone", true, 0));
        return Map.copyOf(result);
    }

    private static OutboxConfigurationException invalid() {
        return new OutboxConfigurationException("JDBC outbox schema does not satisfy the deployed V1 contract");
    }

    private static boolean constraintContains(String definition, String required) {
        return definition != null && definition.replaceAll("[()\\s]+", " ").contains(required)
                && !definition.contains(" OR ");
    }

    private static boolean indexMatches(List<Map<String, Object>> indexes, String name, String columns, Set<String> statuses) {
        return indexes.stream().anyMatch(index -> {
            if (!name.equals(index.get("relname")) || !(index.get("definition") instanceof String definition)
                    || !definition.contains(" USING btree (" + columns + ")")
                    || !(index.get("predicate") instanceof String predicate)
                    || !predicate.contains("status") || predicate.contains(" OR ")) return false;
            var values = new java.util.HashSet<String>();
            var matches = java.util.regex.Pattern.compile("'([^']*)'").matcher(predicate);
            while (matches.find()) values.add(matches.group(1));
            return values.equals(statuses);
        });
    }

    private record Column(String type, boolean nullable, int minimumLength) { }
}
