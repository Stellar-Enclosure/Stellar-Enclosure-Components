package top.egon.cola.component.outbox.migration;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxShardSqlGuardTest {

    @Test
    void everyOutboxMapperStatementContainsAnExactShardKey() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxMessageDAO.class);
        String path = "mapper/outbox/OutboxMessageMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = Map.of("et", new OutboxMessagePO(), "ids", List.of(1L),
                "messageIds", List.of("one"), "id", 1L, "owner", "worker", "messageId", "one",
                "idempotencyKey", "key", "limit", 10);
        for (var method : OutboxMessageDAO.class.getDeclaredMethods()) {
            if (method.isDefault()) { continue; }
            String sql = configuration.getMappedStatement(OutboxMessageDAO.class.getName() + '.' + method.getName())
                    .getBoundSql(parameters).getSql();
            OutboxShardContext.withSlot(0, () -> {
                assertThatCode(() -> OutboxShardSqlGuard.validate(sql)).as("%s: %s", method.getName(), sql)
                        .doesNotThrowAnyException();
                return null;
            });
        }
    }

    @Test
    void rejectsFanoutPredicatesShardMutationAndNestedReads() {
        for (String sql : List.of(
                "UPDATE egon_outbox.egon_cola_outbox_message SET status = 'DEAD' WHERE id = ?",
                "UPDATE egon_outbox.egon_cola_outbox_message SET status = 'DEAD' WHERE shard_slot = ? OR id = ?",
                "UPDATE egon_outbox.egon_cola_outbox_message SET shard_slot = ? WHERE shard_slot = ?",
                "UPDATE egon_outbox.egon_cola_outbox_message SET \"shard_slot\" = ? WHERE shard_slot = ?",
                "INSERT INTO egon_outbox.egon_cola_outbox_message (id) VALUES (?)",
                "SELECT id FROM egon_outbox.egon_cola_outbox_message WHERE shard_slot = ? "
                        + "AND EXISTS (SELECT 1 FROM egon_outbox.egon_cola_outbox_message)")) {
            OutboxShardContext.withSlot(0, () -> {
                assertThatThrownBy(() -> OutboxShardSqlGuard.validate(sql)).hasMessageContaining("OUTBOX_SHARD_SQL_INVALID");
                return null;
            });
        }
        assertThatThrownBy(() -> OutboxShardSqlGuard.validate(
                "SELECT id FROM egon_outbox.egon_cola_outbox_message WHERE shard_slot = ?"))
                .hasMessageContaining("OUTBOX_SHARD_SQL_INVALID");
    }
}
