package top.egon.cola.component.outbox.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.TenantScopedOutboxKey;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.outbox.autoconfigure.OutboxJdbcAutoConfiguration;
import top.egon.cola.component.outbox.autoconfigure.OutboxStateMachineAutoConfiguration;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxAutoConfiguration;
import top.egon.cola.component.outbox.common.exception.OutboxTransactionMismatchException;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.store.JdbcOutboxStore;
import top.egon.cola.component.outbox.store.NewOutboxRecord;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.transaction.OutboxAfterCommitBuffer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Applies the unchanged V1 script only to an explicitly supplied, fresh loopback test database. */
@EnabledIfSystemProperty(named = "egon.outbox.test.postgres.url", matches = ".+")
@Timeout(90)
class JdbcOutboxPostgresqlIntegrationTest {
    private static final String SCHEMA = "outbox_jdbc_it";
    private static final String TABLE = SCHEMA + ".egon_cola_outbox_message";
    private static PGSimpleDataSource source;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;

    @BeforeAll
    static void createFreshTestSchema() {
        String url = System.getProperty("egon.outbox.test.postgres.url");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/outbox_it_[a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("JDBC outbox tests require a loopback outbox_it_ database");
        }
        source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(System.getProperty("user.name"));
        JdbcTemplate admin = new JdbcTemplate(source);
        if (admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Integer.class, SCHEMA) != 0) {
            throw new IllegalStateException("JDBC outbox tests require a fresh test schema");
        }
        admin.execute("CREATE SCHEMA " + SCHEMA);
        source.setCurrentSchema(SCHEMA);
        new ResourceDatabasePopulator(new ClassPathResource("db/transactional-outbox/postgresql/V1__create_transactional_outbox_schema.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE outbox_jdbc_it.business_order (id bigint PRIMARY KEY, state varchar(32) NOT NULL)");
        transactionManager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void cleanOwnedTestTables() {
        jdbc.execute("TRUNCATE TABLE " + TABLE + ", outbox_jdbc_it.business_order");
    }

    @Test
    void satisfies_the_shared_postgresql_store_contract() {
        withStore(fixture -> OutboxStoreContractAssertions.verify(fixture.store(), fixture.transactions(), jdbc, TABLE));
    }

    @Test
    void skips_a_row_locked_by_another_transaction() {
        withStore(fixture -> assertDoesNotThrow(() -> OutboxStoreContractAssertions.verifyLockedRowsAreSkipped(
                fixture.store(), fixture.transactions(), jdbc, TABLE)));
    }

    @Test
    void enqueue_and_business_write_share_one_transaction_and_reject_another_data_source() {
        withStore(fixture -> {
            var message = message(11, "request-1");
            fixture.transactions().executeWithoutResult(status -> {
                jdbc.update("INSERT INTO outbox_jdbc_it.business_order VALUES (1,'created')");
                fixture.outbox().enqueue(message);
                status.setRollbackOnly();
            });
            assertEquals(0, count("outbox_jdbc_it.business_order"));
            assertEquals(0, count(TABLE));
            fixture.transactions().executeWithoutResult(status -> {
                jdbc.update("INSERT INTO outbox_jdbc_it.business_order VALUES (1,'created')");
                fixture.outbox().enqueue(message);
            });
            assertEquals(1, count("outbox_jdbc_it.business_order"));
            assertEquals(1, count(TABLE));

            var other = new PGSimpleDataSource();
            other.setURL(System.getProperty("egon.outbox.test.postgres.url"));
            other.setUser(System.getProperty("user.name"));
            other.setCurrentSchema(SCHEMA);
            var wrongTransaction = new TransactionTemplate(new DataSourceTransactionManager(other));
            assertThrows(OutboxTransactionMismatchException.class, () -> wrongTransaction.executeWithoutResult(
                    status -> fixture.outbox().enqueue(message(11, "request-2"))));
            assertEquals(1, count(TABLE));

            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("direct", "conflict-key", 2)));
            var conflicting = new NewOutboxRecord("other", "conflict-key", "b".repeat(64), "test", "orders",
                    "{}", "application/json", "1", "{}", null, Instant.now(), 2);
            assertThrows(UnexpectedRollbackException.class, () -> fixture.transactions().executeWithoutResult(status -> {
                jdbc.update("INSERT INTO outbox_jdbc_it.business_order VALUES (2,'must-roll-back')");
                assertThrows(OutboxIdempotencyConflictException.class, () -> fixture.store().enqueue(conflicting));
            }));
            assertEquals(1, count("outbox_jdbc_it.business_order"));
            assertEquals(2, count(TABLE));
        });
    }

    @Test
    void concurrent_claimers_receive_disjoint_messages() {
        withStore(fixture -> {
            fixture.transactions().executeWithoutResult(status -> IntStream.range(0, 40).forEach(index ->
                    fixture.store().enqueue(record("claim-" + index, "key-" + index, 3))));
            var gate = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(4)) {
                List<Future<List<OutboxRecord>>> claims = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String owner = "worker-" + i;
                    claims.add(workers.submit(() -> {
                        gate.await();
                        return fixture.store().claimDue(10, owner, Duration.ofMinutes(1));
                    }));
                }
                gate.countDown();
                Set<Long> ids = new HashSet<>();
                for (var claim : claims) {
                    for (OutboxRecord record : assertDoesNotThrow(() -> claim.get(30, TimeUnit.SECONDS))) {
                        assertTrue(ids.add(record.id()));
                    }
                }
                assertEquals(40, ids.size());
            }
        });
    }

    @Test
    void retry_dead_letter_redrive_and_cleanup_reuse_the_existing_lifecycle() {
        withStore(fixture -> {
            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("retry", "retry-key", 1)));
            OutboxRecord first = fixture.store().claimDue(1, "worker-a", Duration.ofMinutes(1)).getFirst();
            assertTrue(fixture.store().markRetry(first.id(), "worker-a", Duration.ZERO, "TEMP", "retry\nmessage"));
            OutboxRecord second = fixture.store().claimByMessageIds(List.of("retry"), 1,
                    "worker-b", Duration.ofMinutes(1)).getFirst();
            assertEquals(2, second.attemptCount());
            assertTrue(fixture.store().markDead(second.id(), "worker-b", "OUTBOX_RETRY_EXHAUSTED", "exhausted"));
            jdbc.update("UPDATE " + TABLE + " SET completed_at=clock_timestamp()-interval '2 seconds' WHERE id=?", second.id());
            assertEquals(1, fixture.store().recoverExhausted(10, Duration.ofSeconds(1)));
            OutboxRecord recovered = fixture.store().claimDue(1, "worker-c", Duration.ofMinutes(1)).getFirst();
            assertEquals(1, recovered.attemptCount());
            assertTrue(fixture.store().markSucceeded(recovered.id(), "worker-c", 0));
            assertEquals(1, fixture.store().deleteSucceeded(Duration.ZERO, 10));
        });
    }

    @Test
    void tenant_keys_do_not_deduplicate_across_tenants() {
        withStore(fixture -> {
            fixture.transactions().executeWithoutResult(status -> {
                fixture.store().enqueue(record("tenant-a", TenantScopedOutboxKey.of(1, "publish", "command"), 2));
                fixture.store().enqueue(record("tenant-b", TenantScopedOutboxKey.of(2, "publish", "command"), 2));
            });
            assertEquals(2, count(TABLE));
        });
    }

    @Test
    void missing_unique_index_is_detected_without_repairing_it() {
        String badSchema = "outbox_bad_it";
        jdbc.execute("CREATE SCHEMA " + badSchema);
        var bad = new PGSimpleDataSource();
        bad.setURL(System.getProperty("egon.outbox.test.postgres.url"));
        bad.setUser(System.getProperty("user.name"));
        bad.setCurrentSchema(badSchema);
        new ResourceDatabasePopulator(new ClassPathResource("db/transactional-outbox/postgresql/V1__create_transactional_outbox_schema.sql"))
                .execute(bad);
        jdbc.execute("DROP INDEX " + badSchema + ".uk_outbox_idempotency_key");
        runner(bad, new DataSourceTransactionManager(bad), badSchema).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("outboxSchemaValidator"));
        });
        Integer remains = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname=?",
                Integer.class, badSchema, "uk_outbox_idempotency_key");
        assertEquals(0, remains);

        String wrongSchema = "outbox_wrong_index_it";
        jdbc.execute("CREATE SCHEMA " + wrongSchema);
        var wrong = new PGSimpleDataSource();
        wrong.setURL(System.getProperty("egon.outbox.test.postgres.url"));
        wrong.setUser(System.getProperty("user.name"));
        wrong.setCurrentSchema(wrongSchema);
        new ResourceDatabasePopulator(new ClassPathResource("db/transactional-outbox/postgresql/V1__create_transactional_outbox_schema.sql"))
                .execute(wrong);
        jdbc.execute("DROP INDEX " + wrongSchema + ".idx_outbox_claim");
        jdbc.execute("CREATE INDEX idx_outbox_claim ON " + wrongSchema + ".egon_cola_outbox_message(message_id)");
        runner(wrong, new DataSourceTransactionManager(wrong), wrongSchema).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("outboxSchemaValidator"));
        });
    }

    private void withStore(java.util.function.Consumer<Fixture> action) {
        runner(source, transactionManager, SCHEMA).run(context -> {
            assertNull(context.getStartupFailure());
            OutboxStore store = context.getBean(OutboxStore.class);
            assertInstanceOf(JdbcOutboxStore.class, store);
            action.accept(new Fixture(store, context.getBean(TransactionalOutbox.class),
                    new TransactionTemplate(transactionManager)));
        });
    }

    private ApplicationContextRunner runner(DataSource dataSource, PlatformTransactionManager manager, String schema) {
        return new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("top.egon.cola.component.common.mybatis", "com.baomidou",
                        "org.apache.ibatis", "org.mybatis"))
                .withConfiguration(AutoConfigurations.of(TransactionalOutboxAutoConfiguration.class,
                        OutboxJdbcAutoConfiguration.class, OutboxStateMachineAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(DeliveryHandler.class, () -> new DeliveryHandler() {
                    @Override public String channel() { return "test"; }
                    @Override public void validateDestination(String destination) { }
                    @Override public DeliveryResult deliver(DeliveryContext context) { return DeliveryResult.success(); }
                })
                .withBean(OutboxAfterCommitBuffer.class, () -> mock(OutboxAfterCommitBuffer.class))
                .withPropertyValues("egon.cola.component.transactional-outbox.storage.type=JDBC",
                        "egon.cola.component.transactional-outbox.storage.jdbc.schema=" + schema,
                        "egon.cola.component.transactional-outbox.polling.enabled=false",
                        "egon.cola.component.transactional-outbox.cleanup.enabled=false",
                        "egon.cola.component.transactional-outbox.annotation.enabled=false");
    }

    private OutboxMessage message(long tenant, String command) {
        return OutboxMessage.builder().idempotencyKey(TenantScopedOutboxKey.of(tenant, "publish", command))
                .channel("test").destination("orders").payload("payload").build();
    }

    private NewOutboxRecord record(String id, String key, int attempts) {
        return new NewOutboxRecord(id, key, "a".repeat(64), "test", "orders", "{}", "application/json",
                "1", "{}", null, Instant.now().minusSeconds(1), attempts);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private record Fixture(OutboxStore store, TransactionalOutbox outbox, TransactionTemplate transactions) { }
}
