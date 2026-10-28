package top.egon.cola.component.outbox.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;

import static org.assertj.core.api.Assertions.assertThat;

public class OutboxRedriveSqlTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    public static Timestamp clockTimestamp() { return Timestamp.from(NOW); }

    @Test void redriveSqlParsesThroughThePostgresqlShardingSphereParser() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        String resource = "mapper/outbox/OutboxMessageMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var parser = new org.apache.shardingsphere.infra.parser.ShardingSphereSQLParserEngine(
                org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader.getService(
                        org.apache.shardingsphere.database.connector.core.type.DatabaseType.class, "PostgreSQL"),
                new org.apache.shardingsphere.sql.parser.engine.api.CacheOption(16, 64),
                new org.apache.shardingsphere.sql.parser.engine.api.CacheOption(16, 64));
        OutboxShardContext.withSlot(0, () -> {
            for (String name : List.of("selectExhaustedCandidates", "redriveExhausted")) {
                String sql = configuration.getMappedStatement(OutboxMessageDAO.class.getName() + "." + name)
                        .getBoundSql(Map.of("id", 1L, "version", 2L, "cooldownMillis", 300_000L, "limit", 5,
                                "updateTime", NOW, "updateUserId", "system:outbox")).getSql();
                assertThat(parser.parse(sql, false)).isNotNull();
            }
            return null;
        });
    }

    @Test void onlyOneWorkerRedrivesAnExhaustedMessageAndPermanentFailuresRemainDead() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA egon_outbox");
        jdbc.execute("CREATE ALIAS clock_timestamp FOR 'top.egon.cola.component.outbox.persistence.OutboxRedriveSqlTest.clockTimestamp'");
        jdbc.execute("""
                CREATE TABLE egon_outbox.egon_cola_outbox_message (
                  id BIGINT PRIMARY KEY, shard_slot INT, tenant_id BIGINT, deleted_at TIMESTAMP,
                  version BIGINT, status VARCHAR, last_error_code VARCHAR, completed_at TIMESTAMP WITH TIME ZONE,
                  attempt_count INT, max_attempts INT, next_attempt_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE,
                  update_user_id VARCHAR, update_time TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.update("INSERT INTO egon_outbox.egon_cola_outbox_message (id,shard_slot,tenant_id,version,status,last_error_code,completed_at,attempt_count,max_attempts) VALUES (1,0,0,2,'DEAD','OUTBOX_RETRY_EXHAUSTED',?,10,10),(2,0,0,2,'DEAD','BAD_PAYLOAD',?,10,10),(3,1,0,2,'DEAD','OUTBOX_RETRY_EXHAUSTED',?,10,10),(4,0,0,2,'DEAD','OUTBOX_RETRY_EXHAUSTED',?,1,10)", NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(600));
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        String xml;
        try (var input = new ClassPathResource("mapper/outbox/OutboxMessageMapper.xml").getInputStream()) {
            xml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(xml).contains("#{cooldownMillis} * interval '1 millisecond'");
        // H2 requires a different spelling for PostgreSQL's millisecond interval literal.
        factory.setMapperLocations(new ByteArrayResource(xml.replace("#{cooldownMillis} * interval '1 millisecond'",
                "CAST(#{cooldownMillis} AS BIGINT) * interval '0.001' second")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OutboxMessageDAO.class);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return update(tx, mapper, 1, 0); });
            var b = workers.submit(() -> { start.await(); return update(tx, mapper, 1, 0); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 1);
        }
        assertThat(update(tx, mapper, 2, 0)).isZero();
        assertThat(update(tx, mapper, 3, 0)).isZero();
        assertThat(update(tx, mapper, 4, 0)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM egon_outbox.egon_cola_outbox_message WHERE id=1", String.class)).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM egon_outbox.egon_cola_outbox_message WHERE id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM egon_outbox.egon_cola_outbox_message WHERE id=2", String.class)).isEqualTo("DEAD");
    }

    private static int update(TransactionTemplate tx, OutboxMessageDAO mapper, long id, int slot) {
        return tx.execute(status -> OutboxShardContext.withSlot(slot,
                () -> mapper.redriveExhausted(id, 2L, 300_000L, NOW, "system:outbox")));
    }
}
