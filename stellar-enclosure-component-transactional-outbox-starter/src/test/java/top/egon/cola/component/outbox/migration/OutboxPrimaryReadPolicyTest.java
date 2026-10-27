package top.egon.cola.component.outbox.migration;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPrimaryReadPolicyTest {

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

    private static boolean permits(String sql) throws Exception {
        return OutboxPrimaryReadPolicy.permits((Select) CCJSqlParserUtil.parse(sql));
    }
}
