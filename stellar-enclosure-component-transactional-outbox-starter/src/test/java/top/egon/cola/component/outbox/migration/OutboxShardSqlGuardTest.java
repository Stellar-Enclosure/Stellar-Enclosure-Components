package top.egon.cola.component.outbox.migration;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;
import top.egon.cola.component.outbox.persistence.dao.OutboxQueueMemberDAO;
import top.egon.cola.component.outbox.persistence.dao.OutboxQueueLeaseDAO;
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
                "messageIds", List.of("one"), "channels", List.of("http"),
                "id", 1L, "owner", "worker", "messageId", "one",
                "idempotencyKey", "key", "limit", 10);
        for (var method : OutboxMessageDAO.class.getDeclaredMethods()) {
            if (method.isDefault()) { continue; }
            OutboxShardContext.withSlot(7, () -> {
                var bound = configuration.getMappedStatement(OutboxMessageDAO.class.getName() + '.' + method.getName())
                        .getBoundSql(parameters);
                String sql = bound.getSql();
                bound.getParameterMappings().stream()
                        .map(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                        .filter(property -> property.equals("_outboxSlot") || property.equals("outboxShardSlot"))
                        .forEach(property -> {
                            org.assertj.core.api.Assertions.assertThat(bound.hasAdditionalParameter(property))
                                    .as("%s must bind the shard context parameter %s", method.getName(), property).isTrue();
                            org.assertj.core.api.Assertions.assertThat(bound.getAdditionalParameter(property))
                                    .as("%s.%s", method.getName(), property).isEqualTo(7);
                        });
                assertThatCode(() -> OutboxShardSqlGuard.validate(sql)).as("%s: %s", method.getName(), sql)
                        .doesNotThrowAnyException();
                return null;
            });
        }
    }

    @Test
    void everyCoordinationMapperXmlStatementIsShardBoundAndRequiresItsContext() throws Exception {
        for (Class<?> mapper : List.of(OutboxQueueMemberDAO.class, OutboxQueueLeaseDAO.class)) {
            Configuration configuration = new Configuration();
            configuration.addMapper(mapper);
            String path = "mapper/outbox/" + mapper.getSimpleName().replace("DAO", "Mapper.xml");
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                org.assertj.core.api.Assertions.assertThat(input).as("%s", path).isNotNull();
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
            for (var method : mapper.getDeclaredMethods()) {
                if (method.isDefault()) continue;
                Map<String, Object> parameters = new java.util.LinkedHashMap<>();
                for (var parameter : method.getParameters()) {
                    var name = parameter.getAnnotation(org.apache.ibatis.annotations.Param.class);
                    if (name == null) throw new AssertionError("Mapper parameters must have @Param: " + method);
                    parameters.put(name.value(), renderParameter(parameter.getType(), name.value()));
                }
                OutboxShardContext.withSlot(7, () -> {
                    String sql = configuration.getMappedStatement(mapper.getName()+'.'+method.getName())
                            .getBoundSql(parameters).getSql();
                    assertThatCode(() -> OutboxShardSqlGuard.validate(sql)).as("%s.%s: %s", mapper.getSimpleName(), method.getName(), sql)
                            .doesNotThrowAnyException();
                    return null;
                });
                // Render while bound, then validate exactly the same SQL with no context.
                String sql = OutboxShardContext.withSlot(7, () -> configuration.getMappedStatement(
                        mapper.getName()+'.'+method.getName()).getBoundSql(parameters).getSql());
                assertThatThrownBy(() -> OutboxShardSqlGuard.validate(sql)).as("%s.%s", mapper.getSimpleName(), method.getName())
                        .hasMessageContaining("OUTBOX_SHARD_SQL_INVALID");
            }
        }
    }

    private static Object renderParameter(Class<?> type, String name) throws Exception {
        if (type == String.class) return name.toLowerCase(java.util.Locale.ROOT).contains("capabilit") ? "[\"test\"]" : "test";
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == java.time.Instant.class) return java.time.Instant.parse("2026-11-23T00:00:00Z");
        if (type == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse("2026-11-23T00:00:00Z");
        if (type == java.time.Duration.class) return java.time.Duration.ofSeconds(60);
        if (java.util.Collection.class.isAssignableFrom(type)) return name.toLowerCase(java.util.Locale.ROOT).contains("id") ? List.of(1L) : List.of("test");
        if (type.isEnum()) return type.getEnumConstants()[0];
        return type.getDeclaredConstructor().newInstance();
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

    @Test
    void acceptsSingleShardCoordinationStatements() {
        for (String table : List.of("egon_cola_outbox_queue_member", "egon_cola_outbox_queue_lease")) {
            for (String sql : List.of(
                    "SELECT id FROM egon_outbox." + table + " WHERE shard_slot = ? AND tenant_id = 0 AND deleted_at IS NULL",
                    "UPDATE egon_outbox." + table + " SET expires_at = clock_timestamp() WHERE shard_slot = ? AND id = ?",
                    "INSERT INTO egon_outbox." + table + " (id, shard_slot) VALUES (?, ?)",
                    "DELETE FROM egon_outbox." + table + " WHERE shard_slot = ? AND id = ?")) {
                OutboxShardContext.withSlot(2, () -> {
                    assertThatCode(() -> OutboxShardSqlGuard.validate(sql)).as("%s", sql)
                            .doesNotThrowAnyException();
                    return null;
                });
            }
        }
    }

    @Test
    void coordinationTablesRequireAContextAndExactMandatoryShardKey() {
        for (String table : List.of("egon_cola_outbox_queue_member", "egon_cola_outbox_queue_lease")) {
            String qualified = "egon_outbox." + table;
            assertThatThrownBy(() -> OutboxShardSqlGuard.validate(
                    "SELECT id FROM " + qualified + " WHERE shard_slot = ?"))
                    .as("coordination cannot bypass its context: %s", table)
                    .hasMessageContaining("OUTBOX_SHARD_SQL_INVALID");
            for (String sql : List.of(
                    "SELECT id FROM " + qualified + " WHERE id = ?",
                    "SELECT id FROM " + qualified + " WHERE shard_slot = ? OR id = ?",
                    "SELECT id FROM " + qualified + " WHERE shard_slot IN (?, ?)",
                    "SELECT id FROM " + qualified + " WHERE shard_slot >= ?",
                    "UPDATE " + qualified + " SET shard_slot = ? WHERE shard_slot = ?",
                    "UPDATE " + qualified + " SET \"shard_slot\" = ? WHERE shard_slot = ?",
                    "INSERT INTO " + qualified + " (id) VALUES (?)",
                    "INSERT INTO " + qualified + " (id, shard_slot) VALUES (?, ?) "
                            + "ON CONFLICT (id) DO UPDATE SET shard_slot = excluded.shard_slot",
                    "SELECT id FROM " + qualified + " WHERE shard_slot = ? AND EXISTS (SELECT 1 FROM orders)",
                    "SELECT q.id FROM " + qualified + " q JOIN orders o ON o.id = q.id WHERE q.shard_slot = ?",
                    "WITH candidates AS (SELECT id FROM " + qualified + " WHERE shard_slot = ?) SELECT id FROM candidates",
                    "DELETE FROM " + qualified + " WHERE shard_slot = ?; DELETE FROM orders")) {
                OutboxShardContext.withSlot(2, () -> {
                    assertThatThrownBy(() -> OutboxShardSqlGuard.validate(sql)).as("%s", sql)
                            .hasMessageContaining("OUTBOX_SHARD_SQL_INVALID");
                    return null;
                });
            }
        }
    }
}
