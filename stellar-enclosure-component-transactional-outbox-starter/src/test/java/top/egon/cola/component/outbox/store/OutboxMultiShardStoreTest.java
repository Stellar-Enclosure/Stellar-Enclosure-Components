package top.egon.cola.component.outbox.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaTechnicalGroupShardingStrategy;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.api.OutboxShardIdentity;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.persistence.OutboxSchemaMetadataValidator;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;
import top.egon.cola.component.outbox.persistence.OutboxShardRouting;
import top.egon.cola.component.outbox.persistence.OutboxTechnicalContextExecutor;
import top.egon.cola.component.outbox.persistence.converter.OutboxMessageConverter;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.persistence.repository.OutboxMessageRepository;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleSignalEnum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises shard-by-shard orchestration with real H2 transactions and row locks. */
class OutboxMultiShardStoreTest {

    @BeforeAll
    static void initializeIds() {
        SnowflakeIdGenerator.initialize(733L, Duration.ofMillis(5));
    }

    @Test
    void shardAwareOverloadsKeepTheInterfaceMethodValidationContract() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().getConstraintsForClass(MybatisPlusOutboxStore.class)).isNotNull();
        }
    }

    @Test
    void businessWriteAndEnqueueShareTheShardAndRollBackTogether() {
        Fixture fixture = fixture();
        fixture.jdbc.execute("CREATE TABLE business (id BIGINT PRIMARY KEY, state VARCHAR(20))");
        fixture.jdbc.update("INSERT INTO business VALUES (1, 'original')");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        String messageId = OutboxShardIdentity.forGroup("group_b", "group_a", "event-1");
        String key = OutboxShardIdentity.forGroup("group_b", "group_a", "order-1");
        transaction.executeWithoutResult(status -> {
            fixture.jdbc.update("UPDATE business SET state = 'changed' WHERE id = 1");
            EgonColaTransactionWriteTarget.capture(fixture.dataSource).pin("group_b", null);
            fixture.store.enqueue(new NewOutboxRecord(messageId, key, "a".repeat(64), "test", "sink", "{}",
                    "application/json", null, "{}", null, Instant.EPOCH, 3));
            assertThat(fixture.jdbc.queryForObject("SELECT shard_slot FROM messages", Integer.class)).isEqualTo(fixture.secondSlot);
            status.setRollbackOnly();
        });
        assertThat(fixture.jdbc.queryForObject("SELECT state FROM business WHERE id = 1", String.class)).isEqualTo("original");
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void aMismatchedGlobalIdentityMarksTheBusinessTransactionRollbackOnly() {
        Fixture fixture = fixture();
        fixture.jdbc.execute("CREATE TABLE business (id BIGINT PRIMARY KEY, state VARCHAR(20))");
        fixture.jdbc.update("INSERT INTO business VALUES (1, 'original')");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            fixture.jdbc.update("UPDATE business SET state = 'changed' WHERE id = 1");
            EgonColaTransactionWriteTarget.capture(fixture.dataSource).pin("group_b", null);
            assertThatThrownBy(() -> fixture.store.enqueue(new NewOutboxRecord("legacy-id", "legacy-key",
                    "a".repeat(64), "test", "sink", "{}", "application/json", null, "{}", null,
                    Instant.EPOCH, 3))).hasMessage("OUTBOX_SHARD_IDENTITY_MISMATCH");
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(fixture.jdbc.queryForObject("SELECT state FROM business WHERE id = 1", String.class)).isEqualTo("original");
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void anExplicitGroupSupportsEnqueueBeforeBusinessWriteAndRejectsAConflictingWrite() {
        Fixture fixture = fixture();
        fixture.jdbc.execute("CREATE TABLE business (id BIGINT PRIMARY KEY, state VARCHAR(20))");
        fixture.jdbc.update("INSERT INTO business VALUES (1, 'original')");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        transaction.executeWithoutResult(status -> {
            fixture.store.selectShard("group_b");
            String messageId = fixture.store.prepareGeneratedMessageId("generated");
            String key = OutboxShardIdentity.forGroup("group_b", "group_a", "key");
            fixture.store.enqueue(new NewOutboxRecord(messageId, key, "a".repeat(64), "test", "sink", "{}",
                    "application/json", null, "{}", null, Instant.EPOCH, 3));
            fixture.jdbc.update("UPDATE business SET state = 'committed' WHERE id = 1");
            EgonColaTransactionWriteTarget.capture(fixture.dataSource).pin("group_b", null);
        });
        assertThat(fixture.jdbc.queryForObject("SELECT shard_slot FROM messages", Integer.class)).isEqualTo(fixture.secondSlot);
        assertThat(fixture.jdbc.queryForObject("SELECT state FROM business WHERE id = 1", String.class)).isEqualTo("committed");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            fixture.store.selectShard("group_b");
            String messageId = fixture.store.prepareGeneratedMessageId("another");
            fixture.store.enqueue(new NewOutboxRecord(messageId, null, "a".repeat(64), "test", "sink", "{}",
                    "application/json", null, "{}", null, Instant.EPOCH, 3));
            assertThatThrownBy(() -> EgonColaTransactionWriteTarget.capture(fixture.dataSource).pin("group_a", null))
                    .hasMessage("LOCAL_WRITE_TARGET_MISMATCH");
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void concurrentWorkersClaimDisjointRowsAcrossBothShards() throws Exception {
        Fixture fixture = fixture();
        for (int i = 1; i <= 200; i++) {
            fixture.jdbc.update("INSERT INTO messages VALUES (?, ?, 'PENDING', 0, 0, NULL)",
                    i, i <= 100 ? 0 : fixture.secondSlot);
        }
        CyclicBarrier barrier = new CyclicBarrier(4);
        List<OutboxRecord> claimed = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<OutboxRecord>>>();
            for (int worker = 0; worker < 4; worker++) {
                String owner = "worker-" + worker;
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return fixture.store.claimDue(50, owner, Duration.ofSeconds(30));
                }));
            }
            for (var future : futures) {
                claimed.addAll(future.get(20, TimeUnit.SECONDS));
            }
        }
        claimed.addAll(fixture.store.claimDue(200, "worker-last", Duration.ofSeconds(30)));
        assertThat(claimed).hasSize(200);
        assertThat(new HashSet<>(claimed.stream().map(OutboxRecord::id).toList())).hasSize(200);
        assertThat(claimed.stream().map(OutboxRecord::shardSlot).distinct()).containsExactlyInAnyOrder(0, fixture.secondSlot);
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM messages WHERE status = 'PROCESSING'", Integer.class))
                .isEqualTo(200);
        OutboxRecord secondShard = claimed.stream().filter(record -> record.shardSlot() == fixture.secondSlot)
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> fixture.store.markSucceeded(secondShard.id(), secondShard.lockedBy()))
                .hasMessage("OUTBOX_SHARD_SLOT_REQUIRED");
        assertThat(fixture.store.markSucceeded(secondShard.id(), secondShard.lockedBy(), secondShard.shardSlot())).isTrue();
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM messages WHERE id = ?", String.class, secondShard.id()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aFailedClaimRollsBackTheWholeShardTransaction() {
        Fixture fixture = fixture();
        fixture.jdbc.update("INSERT INTO messages VALUES (1, 0, 'PENDING', 0, 0, NULL)");
        fixture.jdbc.update("INSERT INTO messages VALUES (2, 0, 'PENDING', 0, 0, NULL)");
        fixture.failSecond.set(true);

        assertThatThrownBy(() -> fixture.store.claimDue(10, "worker", Duration.ofSeconds(30)))
                .isInstanceOf(OutboxStorageException.class);
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM messages WHERE status = 'PENDING'", Integer.class))
                .isEqualTo(2);
    }

    private static Fixture fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE messages (id BIGINT PRIMARY KEY, shard_slot INT NOT NULL, status VARCHAR(32), "
                + "version BIGINT NOT NULL, attempt_count INT NOT NULL, locked_by VARCHAR(128))");
        List<PhysicalDataSourceProperties> sources = List.of(source("a", "group_a", DataSourceRoleEnum.PRIMARY),
                source("ar", "group_a", DataSourceRoleEnum.REPLICA), source("b", "group_b", DataSourceRoleEnum.PRIMARY),
                source("br", "group_b", DataSourceRoleEnum.REPLICA));
        var properties = EgonColaShardingProperties.builder().mode(EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY).dataSources(sources).build();
        var table = new TableProperties("TECHNICAL_GROUP", "group_a", "shard_slot", null, null,
                "egon_outbox", List.of("group_a", "group_b"));
        var profile = new EgonColaTechnicalGroupShardingStrategy()
                .toRoutingProfile("egon_cola_outbox_message", table, sources);
        int secondSlot = profile.tenantSlotMap().entrySet().stream()
                .filter(entry -> entry.getValue().equals("group_b")).map(Map.Entry::getKey).findFirst().orElseThrow();
        OutboxShardRouting routing = new OutboxShardRouting(dataSource, properties,
                Map.of("egon_cola_outbox_message", profile));
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        AtomicBoolean failSecond = new AtomicBoolean();
        when(repository.selectDueCandidates(anyInt())).thenAnswer(invocation -> jdbc.query(
                "SELECT id, shard_slot, status, version, attempt_count, locked_by FROM messages "
                        + "WHERE shard_slot = ? AND status = 'PENDING' ORDER BY id LIMIT ?",
                (rows, index) -> model(rows), OutboxShardContext.currentSlot(), invocation.getArgument(0)));
        when(repository.tryAcquireTransactionLock(anyLong())).thenAnswer(invocation -> !jdbc.queryForList(
                "SELECT id FROM messages WHERE id = ? AND shard_slot = ? FOR UPDATE SKIP LOCKED",
                Long.class, invocation.getArgument(0), OutboxShardContext.currentSlot()).isEmpty());
        when(repository.updateClaim(anyLong(), anyLong(), any(), any(), anyString(), anyLong(), any(), anyString()))
                .thenAnswer(invocation -> {
                    long id = invocation.getArgument(0);
                    if (id == 2 && failSecond.get()) { throw new DataIntegrityViolationException("injected failure"); }
                    return jdbc.update("UPDATE messages SET status = 'PROCESSING', version = version + 1, "
                                    + "attempt_count = attempt_count + 1, locked_by = ? "
                                    + "WHERE id = ? AND shard_slot = ? AND version = ? AND status = ?",
                            invocation.getArgument(4), id, OutboxShardContext.currentSlot(), invocation.getArgument(1),
                            ((OutboxStatus) invocation.getArgument(2)).name());
                });
        when(repository.selectProcessingForUpdate(anyLong(), anyString())).thenAnswer(invocation -> jdbc.query(
                "SELECT id, shard_slot, status, version, attempt_count, locked_by FROM messages "
                        + "WHERE id = ? AND shard_slot = ? AND status = 'PROCESSING' AND locked_by = ? FOR UPDATE",
                rows -> rows.next() ? model(rows) : null, invocation.getArgument(0), OutboxShardContext.currentSlot(),
                invocation.getArgument(1)));
        when(repository.updateSucceeded(anyLong(), anyLong(), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> jdbc.update("UPDATE messages SET status = 'SUCCEEDED', version = version + 1 "
                                + "WHERE id = ? AND shard_slot = ? AND version = ? AND locked_by = ? AND status = 'PROCESSING'",
                        invocation.getArgument(0), OutboxShardContext.currentSlot(), invocation.getArgument(1), invocation.getArgument(2)));
        OutboxMessageConverter converter = mock(OutboxMessageConverter.class);
        when(converter.toInsertPO(any(NewOutboxRecord.class))).thenAnswer(invocation -> {
            NewOutboxRecord record = invocation.getArgument(0);
            return OutboxMessagePO.builder().messageId(record.messageId()).idempotencyKey(record.idempotencyKey())
                    .messageFingerprint(record.messageFingerprint()).maxAttempts(record.maxAttempts()).build();
        });
        when(repository.insertMessage(any(OutboxMessagePO.class))).thenAnswer(invocation -> {
            OutboxMessagePO row = invocation.getArgument(0);
            return jdbc.update("INSERT INTO messages VALUES (?, ?, ?, 0, 0, NULL)",
                    row.getId(), row.getShardSlot(), row.getStatus().name());
        });
        when(converter.toTarget(any())).thenAnswer(invocation -> {
            OutboxMessagePO row = invocation.getArgument(0);
            return new OutboxRecord(row.getId(), "message-" + row.getId(), null, "a".repeat(64), "test", "sink",
                    "{}", "application/json", null, Map.of(), null, row.getStatus(), row.getAttemptCount(), 3,
                    Instant.EPOCH, row.getLockedBy(), Instant.EPOCH, null, null, Instant.EPOCH, Instant.EPOCH,
                    null, row.getShardSlot());
        });
        OutboxLifecycleService lifecycle = mock(OutboxLifecycleService.class);
        when(lifecycle.evaluate(anyString(), any(OutboxLifecycleSignalEnum.class), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.PROCESSING);
        when(lifecycle.evaluate(eq("__NEW__"), eq(OutboxLifecycleSignalEnum.ENQUEUE), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.PENDING);
        when(lifecycle.evaluate(eq("PROCESSING"), eq(OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED), anyInt(), anyInt()))
                .thenReturn(OutboxStatus.SUCCEEDED);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var store = new MybatisPlusOutboxStore(repository, converter,
                new OutboxTechnicalContextExecutor(new EgonColaMybatisPlusProperties()), lifecycle, transaction,
                mock(OutboxSchemaMetadataValidator.class), Clock.systemUTC(), new OutboxMpStorageProperties(), routing);
        return new Fixture(dataSource, jdbc, store, secondSlot, failSecond);
    }

    private static OutboxMessagePO model(java.sql.ResultSet rows) throws java.sql.SQLException {
        return OutboxMessagePO.builder().id(rows.getLong("id")).shardSlot(rows.getInt("shard_slot"))
                .status(OutboxStatus.valueOf(rows.getString("status"))).version(rows.getLong("version"))
                .attemptCount(rows.getInt("attempt_count")).maxAttempts(3).lockedBy(rows.getString("locked_by")).build();
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/test", "test", "test");
    }

    private record Fixture(JdbcDataSource dataSource, JdbcTemplate jdbc, MybatisPlusOutboxStore store,
                           int secondSlot, AtomicBoolean failSecond) {
    }
}
