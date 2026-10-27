package top.egon.cola.component.outbox.migration;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Locale;
import java.util.Set;

/** Only the outbox's fixed locking and schema-verification reads may reach the primary. */
final class OutboxPrimaryReadPolicy {

    private static final String COLUMNS = "id, shard_slot, tenant_id, create_user_id, create_time, update_user_id, update_time, "
            + "deleted_at, version, message_id, idempotency_key, message_fingerprint, channel, destination, payload, "
            + "content_type, schema_version, headers_json, trace_id, status, attempt_count, max_attempts, "
            + "next_attempt_at, locked_by, locked_until, last_error_code, last_error_message, created_at, updated_at, completed_at";
    private static final String TABLE = "egon_outbox.egon_cola_outbox_message";

    private static final Set<String> ALLOWED = Set.of(
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL AND (message_id = ? OR idempotency_key = ?)"
                    + " ORDER BY id FOR UPDATE"),
            canonical("SELECT pg_try_advisory_xact_lock(id) FROM " + TABLE
                    + " WHERE id = ? AND shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL FOR UPDATE SKIP LOCKED"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE id = ? AND shard_slot = ? AND status = 'PROCESSING' AND locked_by = ? AND tenant_id = 0"
                    + " AND deleted_at IS NULL FOR UPDATE"),
            canonical("SELECT script,type,version,checksum,route_fingerprint,tenant_id"
                    + " FROM egon_outbox.ddl_history ORDER BY version"),
            canonical("SELECT c.conname, pg_catalog.pg_get_constraintdef(c.oid)"
                    + " FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_class t ON t.oid = c.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = ? AND t.relname = ?")
    );

    private OutboxPrimaryReadPolicy() {
    }

    static boolean permits(Select select) {
        return ALLOWED.contains(normalize(select.toString()));
    }

    private static String canonical(String sql) {
        try {
            return normalize(CCJSqlParserUtil.parse(sql).toString());
        } catch (JSQLParserException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
