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
            canonical("SELECT id, shard_slot, tenant_id, create_user_id, create_time, update_user_id, update_time, deleted_at, version, "
                    + "instance_id, capabilities_json, last_seen_at, expires_at, clock_timestamp() AS database_time "
                    + "FROM egon_outbox.egon_cola_outbox_queue_member WHERE shard_slot = ? AND tenant_id = 0 "
                    + "AND deleted_at IS NULL AND expires_at > clock_timestamp() ORDER BY instance_id LIMIT ? FOR KEY SHARE"),
            canonical("SELECT id FROM egon_outbox.egon_cola_outbox_queue_member "
                    + "WHERE shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL "
                    + "AND expires_at <= clock_timestamp() - (? * interval '1 millisecond') ORDER BY id LIMIT ? FOR KEY SHARE"),
            canonical("SELECT id, shard_slot, tenant_id, create_user_id, create_time, update_user_id, update_time, deleted_at, version, "
                    + "channel, owner_instance_id, epoch, expires_at "
                    + "FROM egon_outbox.egon_cola_outbox_queue_lease WHERE shard_slot = ? AND tenant_id = 0 "
                    + "AND deleted_at IS NULL AND expires_at > clock_timestamp() ORDER BY channel LIMIT ? FOR KEY SHARE"),
            canonical("SELECT instance_id, capabilities_json, expires_at, clock_timestamp() AS database_time "
                    + "FROM egon_outbox.egon_cola_outbox_queue_member WHERE shard_slot = ? AND tenant_id = 0 "
                    + "AND deleted_at IS NULL AND expires_at > clock_timestamp() ORDER BY instance_id LIMIT ?"),
            canonical("SELECT id, shard_slot, tenant_id, create_user_id, create_time, update_user_id, update_time, deleted_at, version, "
                    + "channel, owner_instance_id, epoch, expires_at FROM egon_outbox.egon_cola_outbox_queue_lease "
                    + "WHERE shard_slot = ? AND channel = ? AND tenant_id = 0 AND deleted_at IS NULL FOR SHARE"),
            canonical("SELECT channel, owner_instance_id, epoch, expires_at FROM egon_outbox.egon_cola_outbox_queue_lease "
                    + "WHERE shard_slot = ? AND channel = ? AND tenant_id = 0 AND deleted_at IS NULL FOR SHARE"),
            canonical("SELECT channel, owner_instance_id, epoch, expires_at "
                    + "FROM egon_outbox.egon_cola_outbox_queue_lease WHERE shard_slot = ? AND tenant_id = 0 "
                    + "AND deleted_at IS NULL AND expires_at > clock_timestamp() ORDER BY channel LIMIT ?"),
            canonical("SELECT expires_at > clock_timestamp() FROM egon_outbox.egon_cola_outbox_queue_lease "
                    + "WHERE shard_slot = ? AND channel = ? AND tenant_id = 0 AND deleted_at IS NULL "
                    + "AND owner_instance_id = ? AND epoch = ? FOR SHARE"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE shard_slot = ? AND channel = ? AND tenant_id = 0 AND deleted_at IS NULL "
                    + "AND status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= statement_timestamp() "
                    + "ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE NOWAIT"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE shard_slot = ? AND channel = ? AND tenant_id = 0 AND deleted_at IS NULL "
                    + "AND status = 'PROCESSING' AND locked_until <= statement_timestamp() "
                    + "ORDER BY locked_until, id LIMIT ? FOR UPDATE NOWAIT"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE id = ? AND shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL FOR UPDATE"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL AND (message_id = ? OR idempotency_key = ?)"
                    + " ORDER BY id FOR UPDATE"),
            canonical("SELECT pg_try_advisory_xact_lock(id) FROM " + TABLE
                    + " WHERE id = ? AND shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL FOR UPDATE NOWAIT"),
            canonical("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE id = ? AND shard_slot = ? AND status = 'PROCESSING' AND locked_by = ?"
                    + " AND locked_until > clock_timestamp() AND tenant_id = 0"
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
