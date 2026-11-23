package top.egon.cola.component.outbox.migration;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPrimaryReadPolicyTest {

    private static final String MEMBER_SNAPSHOT = "SELECT instance_id, capabilities_json, expires_at, "
            + "clock_timestamp() AS database_time FROM egon_outbox.egon_cola_outbox_queue_member "
            + "WHERE shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL "
            + "AND expires_at > clock_timestamp() ORDER BY instance_id LIMIT ?";

    private static final String QUEUE_AUTHORIZATION = "SELECT channel, owner_instance_id, epoch, expires_at "
            + "FROM egon_outbox.egon_cola_outbox_queue_lease WHERE shard_slot = ? AND channel = ? "
            + "AND tenant_id = 0 AND deleted_at IS NULL FOR SHARE";

    @Test
    void permitsTheExactBoundedMemberSnapshotAndQueueAuthorizationReads() throws Exception {
        assertThat(permits(MEMBER_SNAPSHOT)).isTrue();
        assertThat(permits(QUEUE_AUTHORIZATION)).isTrue();
        assertThat(permits(MEMBER_SNAPSHOT.toLowerCase(java.util.Locale.ROOT)
                .replace(" from ", "\nfrom "))).isTrue();
    }

    @Test
    void coordinationPrimaryReadsCannotBroadenTheirPredicatesProjectionOrLocks() throws Exception {
        for (String approved : new String[]{MEMBER_SNAPSHOT, QUEUE_AUTHORIZATION}) {
            assertThat(permits(approved.replace("shard_slot = ? AND ", "")))
                    .as("shard scope: %s", approved).isFalse();
            assertThat(permits(approved.replace("tenant_id = 0 AND ", "")))
                    .as("technical tenant: %s", approved).isFalse();
            assertThat(permits(approved.replace("deleted_at IS NULL", "deleted_at IS NOT NULL")))
                    .as("soft delete: %s", approved).isFalse();
            assertThat(permits(approved.replace("WHERE shard_slot = ? AND", "WHERE shard_slot = ? OR")))
                    .as("fanout: %s", approved).isFalse();
        }
        assertThat(permits(MEMBER_SNAPSHOT.replace(" LIMIT ?", ""))).isFalse();
        assertThat(permits(MEMBER_SNAPSHOT.replace("expires_at > clock_timestamp()", "expires_at >= clock_timestamp()")))
                .isFalse();
        assertThat(permits(MEMBER_SNAPSHOT.replace("clock_timestamp() AS database_time", "now() AS database_time")))
                .isFalse();
        assertThat(permits(MEMBER_SNAPSHOT.replace("instance_id, capabilities_json, expires_at, clock_timestamp() AS database_time", "*")))
                .isFalse();
        assertThat(permits(QUEUE_AUTHORIZATION.replace(" AND channel = ?", ""))).isFalse();
        assertThat(permits(QUEUE_AUTHORIZATION.replace(" FOR SHARE", ""))).isFalse();
        assertThat(permits(QUEUE_AUTHORIZATION.replace(" FOR SHARE", " FOR UPDATE"))).isFalse();
        assertThat(permits(QUEUE_AUTHORIZATION.replace(" FOR SHARE", " FOR SHARE SKIP LOCKED"))).isFalse();
        assertThat(permits("SELECT epoch FROM egon_outbox.egon_cola_outbox_queue_lease WHERE shard_slot = ? FOR SHARE"))
                .isFalse();
    }

    @Test
    void permitsOnlyTheExactMapperLockingQueriesAndSchemaChecks() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxMessageDAO.class);
        String resource = "mapper/outbox/OutboxMessageMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (String method : new String[]{"selectExistingByIdentity", "tryAcquireTransactionLock", "selectProcessingForUpdate"}) {
            String sql = configuration.getMappedStatement(OutboxMessageDAO.class.getName() + "." + method)
                    .getBoundSql(Map.of("id", 1L, "owner", "worker", "messageId", "message", "idempotencyKey", "key"))
                    .getSql();
            assertThat(OutboxPrimaryReadPolicy.permits((Select) CCJSqlParserUtil.parse(sql)))
                    .as("mapper %s: %s", method, sql).isTrue();
        }
        assertThat(permits("SELECT script,type,version,checksum,route_fingerprint,tenant_id "
                + "FROM egon_outbox.ddl_history ORDER BY version")).isTrue();
        assertThat(permits("SELECT c.conname, pg_catalog.pg_get_constraintdef(c.oid) "
                + "FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace "
                + "WHERE n.nspname = ? AND t.relname = ?")).isTrue();
    }

    @Test
    void rejectsBusinessTablesUnrestrictedOutboxReadsAndSubqueries() throws Exception {
        assertThat(permits("SELECT id FROM orders WHERE id = ? FOR UPDATE")).isFalse();
        assertThat(permits("SELECT * FROM egon_outbox.egon_cola_outbox_message FOR UPDATE")).isFalse();
        assertThat(permits("SELECT pg_try_advisory_xact_lock(id) FROM egon_outbox.egon_cola_outbox_message "
                + "WHERE id = ? AND tenant_id = 0 AND deleted_at IS NULL "
                + "AND EXISTS (SELECT 1 FROM orders) FOR UPDATE SKIP LOCKED")).isFalse();
        assertThat(permits("SELECT pg_try_advisory_xact_lock(id) FROM egon_outbox.egon_cola_outbox_message "
                + "WHERE id = ? AND tenant_id = 0 AND deleted_at IS NULL")).isFalse();
    }

    @Test
    void replicaIdentityLookupIsShardBoundAndCannotUseThePrimaryReadException() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxMessageDAO.class);
        String resource = "mapper/outbox/OutboxMessageMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        OutboxShardContext.withSlot(4, () -> {
            var sql = configuration.getMappedStatement(OutboxMessageDAO.class.getName() + ".selectReplicaByMessageId")
                    .getBoundSql(Map.of("messageId", "message"));
            assertThat(sql.getAdditionalParameter("_outboxSlot")).isEqualTo(4);
            assertThat(sql.getSql().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))
                    .contains("shard_slot = ?", "tenant_id = 0", "message_id = ?", "deleted_at is null")
                    .doesNotContain("for update", "for share");
            org.assertj.core.api.Assertions.assertThatCode(() -> OutboxShardSqlGuard.validate(sql.getSql()))
                    .doesNotThrowAnyException();
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    assertThat(permits(sql.getSql())).isFalse()).doesNotThrowAnyException();
            return null;
        });
    }

    private static boolean permits(String sql) throws Exception {
        return OutboxPrimaryReadPolicy.permits((Select) CCJSqlParserUtil.parse(sql));
    }
}
