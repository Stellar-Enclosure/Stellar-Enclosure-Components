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
import org.springframework.jdbc.datasource.DelegatingDataSource;
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
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxTransactionMismatchException;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.store.JdbcOutboxStore;
import top.egon.cola.component.outbox.store.NewOutboxRecord;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.store.OutboxClaimCommand;
import top.egon.cola.component.outbox.store.OutboxClaimBatchBO;
import top.egon.cola.component.outbox.store.OutboxFinishCommand;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationCommand;
import top.egon.cola.component.outbox.store.OutboxQueueKey;
import top.egon.cola.component.outbox.store.OutboxQueueLease;
import top.egon.cola.component.outbox.persistence.converter.OutboxHeadersConverter;
import top.egon.cola.component.outbox.retry.OutboxRetryPolicy;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.transaction.OutboxAfterCommitBuffer;
import top.egon.cola.component.outbox.transaction.OutboxTransactionGuard;
import top.egon.cola.component.outbox.validation.OutboxMessageValidator;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Applies the published V1 and additive V2 only to an explicitly supplied, fresh loopback test database. */
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
        new ResourceDatabasePopulator(new ClassPathResource("db/transactional-outbox/postgresql/V2__add_outbox_queue_coordination.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE outbox_jdbc_it.business_order (id bigint PRIMARY KEY, state varchar(32) NOT NULL)");
        transactionManager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void cleanOwnedTestTables() {
        jdbc.execute("TRUNCATE TABLE " + TABLE + ", outbox_jdbc_it.business_order, "
                + SCHEMA + ".egon_cola_outbox_queue_member, " + SCHEMA + ".egon_cola_outbox_queue_lease");
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

    @Test
    void safe_local_claims_obey_reserved_batch_limits_and_preserve_legacy_guards() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            for (int capacity : List.of(1, 2, 4)) {
                jdbc.execute("TRUNCATE TABLE " + TABLE);
                fixture.transactions().executeWithoutResult(status -> IntStream.range(0, 6).forEach(index ->
                        fixture.store().enqueue(record("capacity-" + capacity + '-' + index,
                                "capacity-key-" + capacity + '-' + index, 3))));
                OutboxClaimBatchBO claimed = fixture.store().claim(claim(capacity, "batch-" + capacity, null));
                assertEquals(capacity, claimed.getRecords().size());
                assertNotNull(claimed.getDatabaseTime());
                assertFalse(claimed.isNotOwner());
                assertEquals(0, claimed.getExhaustedCount());
                assertEquals(capacity, jdbc.queryForObject("SELECT count(*) FROM " + TABLE
                        + " WHERE status='PROCESSING' AND locked_by=? AND attempt_count=1", Integer.class, "batch-" + capacity));
                assertThrows(UnsupportedOperationException.class, () -> claimed.getRecords().clear());
            }
            assertThrows(OutboxConfigurationException.class, () -> fixture.store().claimDue(4, "legacy", Duration.ofMinutes(1)));
            assertThrows(OutboxConfigurationException.class, () -> fixture.store().claimByMessageIds(
                    List.of("capacity-4-5"), 1, "legacy", Duration.ofMinutes(1)));
            assertThrows(IllegalArgumentException.class, () -> fixture.store().claim(
                    claim(1, "wrong-slot", null).setQueueKey(new OutboxQueueKey(1, "test"))));
            fixture.properties().getDispatch().setConsumptionEnabled(false);
            assertThrows(OutboxConfigurationException.class, () -> fixture.store().claim(claim(1, "paused", null)));
        });
    }

    @Test
    void queue_takeover_fences_old_claims_but_preserves_a_valid_inflight_message_ack() {
        withSafeStore(DispatchMode.COORDINATED, fixture -> {
            fixture.store().validateSchema();
            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("inflight", "inflight-key", 3)));
            OutboxQueueLease first = fixture.store().coordinate(coordination("instance-a", Set.of("test")))
                    .getLeases().get("test");
            assertNotNull(first);
            assertEquals(1, first.epoch());
            var other = fixture.store().coordinate(coordination("instance-b", Set.of("test")));
            assertEquals(2, other.getMembers().size());
            assertEquals(first, other.getLeases().get("test"));
            OutboxRecord running = fixture.store().claim(claim(1, "old-message-token", first)).getRecords().getFirst();

            jdbc.update("UPDATE " + SCHEMA + ".egon_cola_outbox_queue_lease "
                    + "SET expires_at=clock_timestamp()-interval '1 second' WHERE channel='test'");
            OutboxQueueLease successor = fixture.store().coordinate(coordination("instance-b", Set.of("test")))
                    .getLeases().get("test");
            assertNotNull(successor);
            assertEquals(first.epoch() + 1, successor.epoch());
            assertTrue(fixture.store().claim(claim(1, "stale-owner", first)).isNotOwner());
            assertEquals(OutboxFinishStatusEnum.APPLIED,
                    fixture.store().finishDelivery(finish(running, DeliveryResult.success())));
            assertEquals(OutboxFinishStatusEnum.ALREADY_TERMINAL,
                    fixture.store().finishDelivery(finish(running, DeliveryResult.success())));

            var staleRenewal = coordination("instance-a", Set.of()).setRenew(List.of(first));
            assertEquals(successor, fixture.store().coordinate(staleRenewal).getLeases().get("test"));
            var renew = coordination("instance-b", Set.of()).setRenew(List.of(successor));
            assertEquals(successor.epoch(), fixture.store().coordinate(renew).getLeases().get("test").epoch());
            fixture.store().coordinate(coordination("instance-b", Set.of()).setRelease(List.of(successor)).setDraining(true));
            OutboxQueueLease reacquired = fixture.store().coordinate(coordination("instance-a", Set.of("test")))
                    .getLeases().get("test");
            assertEquals(successor.epoch() + 1, reacquired.epoch());
        });
    }

    @Test
    void safe_claim_terminalizes_exhausted_pending_retry_and_expired_processing_rows() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> {
                fixture.store().enqueue(record("exhausted-pending", "exhausted-pending-key", 2));
                fixture.store().enqueue(record("exhausted-retry", "exhausted-retry-key", 2));
                fixture.store().enqueue(record("exhausted-running", "exhausted-running-key", 2));
            });
            jdbc.update("UPDATE " + TABLE + " SET attempt_count=2");
            jdbc.update("UPDATE " + TABLE + " SET status='RETRY_WAIT', attempt_count=3 WHERE message_id='exhausted-retry'");
            jdbc.update("UPDATE " + TABLE + " SET status='PROCESSING', locked_by='crashed', "
                    + "locked_until=clock_timestamp()-interval '1 second' WHERE message_id='exhausted-running'");
            OutboxClaimBatchBO result = fixture.store().claim(claim(3, "must-not-run", null).setDueQuota(2).setReclaimQuota(1));
            assertTrue(result.getRecords().isEmpty());
            assertEquals(3, result.getExhaustedCount());
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM " + TABLE
                    + " WHERE status='DEAD' AND locked_by IS NULL AND locked_until IS NULL "
                    + "AND last_error_code='OUTBOX_RETRY_EXHAUSTED' AND completed_at IS NOT NULL", Integer.class));
            assertThrows(OutboxConfigurationException.class, () -> fixture.store().recoverExhausted(10, Duration.ofSeconds(1)));
        });
    }

    @Test
    void reserved_due_and_reclaim_quotas_both_progress_and_old_message_tokens_cannot_ack() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> IntStream.range(0, 3).forEach(index ->
                    fixture.store().enqueue(record("mixed-" + index, "mixed-key-" + index, 3))));
            OutboxRecord crashed = fixture.store().claim(claim(1, "crashed-token", null)).getRecords().getFirst();
            jdbc.update("UPDATE " + TABLE + " SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?", crashed.id());
            OutboxClaimBatchBO batch = fixture.store().claim(claim(2, "replacement-token", null).setDueQuota(1).setReclaimQuota(1));
            assertEquals(2, batch.getRecords().size());
            assertEquals(Set.of(1, 2), batch.getRecords().stream().map(OutboxRecord::attemptCount).collect(java.util.stream.Collectors.toSet()));
            assertEquals(OutboxFinishStatusEnum.LEASE_LOST,
                    fixture.store().finishDelivery(finish(crashed, DeliveryResult.success())));
            OutboxRecord reclaimed = batch.getRecords().stream().filter(row -> row.id() == crashed.id()).findFirst().orElseThrow();
            assertEquals(OutboxFinishStatusEnum.APPLIED,
                    fixture.store().finishDelivery(finish(reclaimed, DeliveryResult.success())));
        });
    }

    @Test
    void authoritative_finish_uses_persisted_budget_and_the_existing_retry_policy() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> {
                fixture.store().enqueue(record("last-attempt", "last-attempt-key", 1));
                fixture.store().enqueue(record("retryable", "retryable-key", 2));
            });
            List<OutboxRecord> rows = fixture.store().claim(claim(2, "finish-token", null)).getRecords();
            OutboxRecord exhausted = rows.stream().filter(row -> row.maxAttempts() == 1).findFirst().orElseThrow();
            OutboxRecord retryable = rows.stream().filter(row -> row.maxAttempts() == 2).findFirst().orElseThrow();
            assertEquals(OutboxFinishStatusEnum.LEASE_LOST, fixture.store().finishDelivery(
                    finish(retryable, DeliveryResult.success()).setMessageId("wrong-message")));
            assertEquals(OutboxFinishStatusEnum.APPLIED, fixture.store().finishDelivery(
                    finish(exhausted, DeliveryResult.retryableFailure("TEMP", "retry\nfailed"))));
            assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE id=?", String.class, exhausted.id()));
            assertEquals("OUTBOX_RETRY_EXHAUSTED", jdbc.queryForObject("SELECT last_error_code FROM " + TABLE + " WHERE id=?", String.class, exhausted.id()));
            assertEquals(OutboxFinishStatusEnum.APPLIED, fixture.store().finishDelivery(
                    finish(retryable, DeliveryResult.retryableFailure("TEMP", "retry\nfailed"))));
            assertTrue(jdbc.queryForObject("SELECT next_attempt_at > updated_at AND status='RETRY_WAIT' "
                    + "AND locked_by IS NULL AND last_error_message='retry failed' FROM " + TABLE + " WHERE id=?", Boolean.class, retryable.id()));
            assertEquals(OutboxFinishStatusEnum.LEASE_LOST,
                    fixture.store().finishDelivery(finish(retryable, DeliveryResult.success())));
        });
    }

    @Test
    void finish_samples_database_time_after_waiting_for_the_authoritative_row_lock() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("locked-ack", "locked-ack-key", 3)));
            OutboxRecord row = fixture.store().claim(claim(1, "locked-ack-token", null)).getRecords().getFirst();
            assertDoesNotThrow(() -> {
                try (var connection = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("SELECT id FROM " + TABLE + " WHERE id=? FOR UPDATE")) {
                        statement.setLong(1, row.id());
                        statement.executeQuery().close();
                    }
                    CountDownLatch started = new CountDownLatch(1);
                    Future<OutboxFinishStatusEnum> finish = executor.submit(() -> {
                        started.countDown();
                        return fixture.store().finishDelivery(finish(row, DeliveryResult.success()));
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                    try (var statement = connection.prepareStatement("UPDATE " + TABLE
                            + " SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?")) {
                        statement.setLong(1, row.id());
                        statement.executeUpdate();
                    }
                    connection.commit();
                    assertEquals(OutboxFinishStatusEnum.LEASE_LOST, finish.get(5, TimeUnit.SECONDS));
                }
            });
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE id=?", String.class, row.id()));
        });
    }

    @Test
    void queue_authorization_samples_database_time_after_waiting_for_the_queue_row_lock() {
        withSafeStore(DispatchMode.COORDINATED, fixture -> {
            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("locked-queue", "locked-queue-key", 3)));
            OutboxQueueLease lease = fixture.store().coordinate(coordination("locked-owner", Set.of("test")))
                    .getLeases().get("test");
            assertDoesNotThrow(() -> {
                try (var connection = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("SELECT id FROM " + SCHEMA
                            + ".egon_cola_outbox_queue_lease WHERE channel='test' FOR UPDATE")) {
                        statement.executeQuery().close();
                    }
                    CountDownLatch started = new CountDownLatch(1);
                    Future<OutboxClaimBatchBO> claim = executor.submit(() -> {
                        started.countDown();
                        return fixture.store().claim(claim(1, "expired-queue-token", lease));
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> claim.get(100, TimeUnit.MILLISECONDS));
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE " + SCHEMA + ".egon_cola_outbox_queue_lease "
                                + "SET expires_at=clock_timestamp()-interval '1 second' WHERE channel='test'");
                    }
                    connection.commit();
                    OutboxClaimBatchBO result = claim.get(5, TimeUnit.SECONDS);
                    assertTrue(result.isNotOwner());
                    assertTrue(result.getRecords().isEmpty());
                }
            });
            assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM " + TABLE
                    + " WHERE message_id='locked-queue'", Integer.class));
        });
    }

    @Test
    void unknown_claim_and_ack_commits_leave_rows_for_lease_recovery_and_confirmation() {
        AtomicBoolean failCommit = new AtomicBoolean();
        withSafeStore(DispatchMode.LOCAL, commitUncertainSource(failCommit), fixture -> {
            fixture.transactions().executeWithoutResult(status -> fixture.store().enqueue(record("unknown", "unknown-key", 3)));
            failCommit.set(true);
            OutboxStorageException unknown = assertThrows(OutboxStorageException.class,
                    () -> fixture.store().claim(claim(1, "unknown-claim-token", null)));
            assertTrue(unknown.getMessage().contains("INDETERMINATE"));
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE message_id='unknown'", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM " + TABLE + " WHERE message_id='unknown'", Integer.class));
            jdbc.update("UPDATE " + TABLE + " SET locked_until=clock_timestamp()-interval '1 second' WHERE message_id='unknown'");
            OutboxRecord recovered = fixture.store().claim(claim(1, "recovered-token", null)).getRecords().getFirst();
            assertEquals(2, recovered.attemptCount());
            failCommit.set(true);
            OutboxFinishCommand confirmation = finish(recovered, DeliveryResult.success());
            assertEquals(OutboxFinishStatusEnum.INDETERMINATE, fixture.store().finishDelivery(confirmation));
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE message_id='unknown'", String.class));
            assertEquals(OutboxFinishStatusEnum.ALREADY_TERMINAL, fixture.store().finishDelivery(confirmation));
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM " + TABLE + " WHERE message_id='unknown'", Integer.class));
        });
    }

    @Test
    void concurrent_safe_local_batches_remain_bounded_and_disjoint() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> IntStream.range(0, 16).forEach(index ->
                    fixture.store().enqueue(record("safe-concurrent-" + index, "safe-concurrent-key-" + index, 3))));
            CountDownLatch gate = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(4)) {
                List<Future<OutboxClaimBatchBO>> batches = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    String token = "safe-worker-" + index;
                    batches.add(executor.submit(() -> {
                        gate.await();
                        return fixture.store().claim(claim(4, token, null));
                    }));
                }
                gate.countDown();
                Set<Long> ids = new HashSet<>();
                for (Future<OutboxClaimBatchBO> batch : batches) {
                    List<OutboxRecord> records = assertDoesNotThrow(() -> batch.get(10, TimeUnit.SECONDS)).getRecords();
                    assertEquals(4, records.size());
                    records.forEach(row -> assertTrue(ids.add(row.id())));
                }
                assertEquals(16, ids.size());
            }
        });
    }

    @Test
    void safe_legacy_marks_keep_budget_guards_and_inflight_ack_remains_available_when_paused() {
        withSafeStore(DispatchMode.LOCAL, fixture -> {
            fixture.transactions().executeWithoutResult(status -> {
                fixture.store().enqueue(record("legacy-mark-budget", "legacy-mark-budget-key", 1));
                fixture.store().enqueue(record("pause-ack", "pause-ack-key", 3));
            });
            List<OutboxRecord> records = fixture.store().claim(claim(2, "compatible-mark-token", null)).getRecords();
            OutboxRecord exhausted = records.stream().filter(row -> row.maxAttempts() == 1).findFirst().orElseThrow();
            assertTrue(fixture.store().markRetry(exhausted.id(), exhausted.lockedBy(), Duration.ZERO, "TEMP", "failed"));
            assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE id=?", String.class, exhausted.id()));
            assertEquals("OUTBOX_RETRY_EXHAUSTED", jdbc.queryForObject("SELECT last_error_code FROM " + TABLE + " WHERE id=?", String.class, exhausted.id()));
            OutboxRecord inflight = records.stream().filter(row -> row.maxAttempts() == 3).findFirst().orElseThrow();
            fixture.properties().getDispatch().setConsumptionEnabled(false);
            assertTrue(fixture.store().markSucceeded(inflight.id(), inflight.lockedBy()));
            fixture.properties().getDispatch().setConsumptionEnabled(true).setMode(null);
            assertThrows(OutboxConfigurationException.class, () -> fixture.store().claim(claim(1, "invalid-mode", null)));
        });
    }

    private void withSafeStore(DispatchMode mode, java.util.function.Consumer<SafeFixture> action) {
        withSafeStore(mode, source, action);
    }

    private void withSafeStore(DispatchMode mode, DataSource dataSource, java.util.function.Consumer<SafeFixture> action) {
        PlatformTransactionManager manager = dataSource == source ? transactionManager : new DataSourceTransactionManager(dataSource);
        runner(dataSource, manager, SCHEMA).run(context -> {
            assertNull(context.getStartupFailure());
            TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
            properties.getDispatch().setMode(mode).setMaxClaimBatch(4);
            TransactionTemplate worker = new TransactionTemplate(manager);
            worker.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            OutboxMessageValidator validator = context.getBean(OutboxMessageValidator.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            JdbcOutboxStore store = assertDoesNotThrow(() -> JdbcOutboxStore.class.getConstructor(
                    DataSource.class, String.class, TransactionTemplate.class, OutboxTransactionGuard.class,
                    OutboxLifecycleService.class, OutboxHeadersConverter.class, OutboxMessageValidator.class,
                    ValidationUtils.class, TransactionalOutboxProperties.class, ObjectMapper.class, OutboxRetryPolicy.class)
                    .newInstance(dataSource, SCHEMA, worker, context.getBean(OutboxTransactionGuard.class),
                            context.getBean(OutboxLifecycleService.class), new OutboxHeadersConverter(mapper, validator),
                            validator, context.getBean(ValidationUtils.class), properties, mapper,
                            (OutboxRetryPolicy) attempt -> Duration.ofMillis(100)));
            assertTrue(store.supportsDispatchMode(mode));
            action.accept(new SafeFixture(store, new TransactionTemplate(manager), properties));
        });
    }

    private static DataSource commitUncertainSource(AtomicBoolean armed) {
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object result = method.invoke(connection, arguments);
                                if (method.getName().equals("commit") && armed.compareAndSet(true, false)) {
                                    throw new SQLException("Injected loss of commit confirmation", "08006");
                                }
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }

    private static OutboxClaimCommand claim(int limit, String token, OutboxQueueLease lease) {
        return OutboxClaimCommand.builder().queueKey(new OutboxQueueKey(0, "test")).limit(limit)
                .claimToken(token).leaseDuration(Duration.ofSeconds(60)).queueLease(lease).dueQuota(limit).build();
    }

    private static OutboxQueueCoordinationCommand coordination(String instance, Set<String> acquire) {
        return OutboxQueueCoordinationCommand.builder().shardSlot(0).instanceId(instance)
                .channels(Set.of("test")).acquire(acquire).build();
    }

    private static OutboxFinishCommand finish(OutboxRecord row, DeliveryResult result) {
        return OutboxFinishCommand.builder().shardSlot(0).id(row.id()).messageId(row.messageId())
                .attemptCount(row.attemptCount()).claimToken(row.lockedBy()).deliveryResult(result).build();
    }

    private record SafeFixture(JdbcOutboxStore store, TransactionTemplate transactions,
                               TransactionalOutboxProperties properties) { }

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
                        "egon.cola.component.transactional-outbox.state-machine.execution-timeout=500ms",
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
