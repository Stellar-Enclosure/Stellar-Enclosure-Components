package top.egon.cola.component.outbox.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaPhysicalDataSourceFactory;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingDataSourceBootstrapper;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaTechnicalGroupShardingStrategy;
import top.egon.cola.component.outbox.api.OutboxShardIdentity;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.migration.OutboxLogicalDataSourceFactory;
import top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer;
import top.egon.cola.component.outbox.persistence.OutboxSchemaMetadataValidator;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit opt-in only: two dedicated, empty PostgreSQL primary/replica groups are required. */
@EnabledIfEnvironmentVariable(named = "EGON_OUTBOX_PG_MULTISHARD_IT", matches = "true")
class OutboxPostgresqlMultishardIntegrationTest {

    @Test
    void atomicShardWritesAndConcurrentClaimsStayOnTheirSelectedPrimaries() throws Exception {
        String user = required("EGON_OUTBOX_PG_USER");
        String password = required("EGON_OUTBOX_PG_PASSWORD");
        String a = required("EGON_OUTBOX_PG_SHARD_0_PRIMARY_URL");
        String ar = required("EGON_OUTBOX_PG_SHARD_0_REPLICA_URL");
        String b = required("EGON_OUTBOX_PG_SHARD_1_PRIMARY_URL");
        String br = required("EGON_OUTBOX_PG_SHARD_1_REPLICA_URL");
        JdbcTemplate primaryA = jdbc(a, user, password), primaryB = jdbc(b, user, password);
        JdbcTemplate replicaA = jdbc(ar, user, password), replicaB = jdbc(br, user, password);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String businessA = "outbox_a_" + suffix, businessB = "outbox_b_" + suffix;
        boolean schemaA = false, schemaB = false, tableA = false, tableB = false;
        DataSource logical = null;
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            primaryA.execute("CREATE SCHEMA egon_outbox");
            schemaA = true;
            primaryB.execute("CREATE SCHEMA egon_outbox");
            schemaB = true;
            primaryA.execute("CREATE TABLE public." + businessA + " (id bigint PRIMARY KEY, state varchar(20))");
            tableA = true;
            primaryB.execute("CREATE TABLE public." + businessB + " (id bigint PRIMARY KEY, state varchar(20))");
            tableB = true;
            primaryA.update("INSERT INTO public." + businessA + " VALUES (1, 'initial')");
            primaryB.update("INSERT INTO public." + businessB + " VALUES (1, 'initial')");
            ValidationUtils validation = new ValidationUtils(validators.getValidator());
            var sources = List.of(source("a_primary", "group_a", DataSourceRoleEnum.PRIMARY, a, user, password),
                    source("a_replica", "group_a", DataSourceRoleEnum.REPLICA, ar, user, password),
                    source("b_primary", "group_b", DataSourceRoleEnum.PRIMARY, b, user, password),
                    source("b_replica", "group_b", DataSourceRoleEnum.REPLICA, br, user, password));
            var outbox = new TableProperties("TECHNICAL_GROUP", "group_a", "shard_slot", null, null,
                    "egon_outbox", List.of("group_a", "group_b"));
            var history = new TableProperties("SINGLE", "group_a", null, null, null, "egon_outbox");
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY).dataSources(sources)
                    .tables(Map.of("egon_cola_outbox_message", outbox,
                            "egon_cola_outbox_queue_member", outbox,
                            "egon_cola_outbox_queue_lease", outbox, "ddl_history", history,
                            businessA, new TableProperties("SINGLE", "group_a", null, null, null),
                            businessB, new TableProperties("SINGLE", "group_b", null, null, null))).build();
            var strategy = new EgonColaShardingStrategyFactory(validation, Map.of(
                    "SINGLE", new EgonColaSingleTableShardingStrategy(),
                    "TECHNICAL_GROUP", new EgonColaTechnicalGroupShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(strategy, new DefaultResourceLoader(), new StandardEnvironment());
            var topology = new EgonColaShardingTopologyValidator(validation);
            var runner = new EgonColaPostgreDdlRunner(validation, new PathMatchingResourcePatternResolver(),
                    Clock.systemUTC(), Duration.ofSeconds(10), Duration.ofSeconds(30));
            var storage = new OutboxMpStorageProperties();
            var initializer = new OutboxManagedDdlInitializer(runner, topology, validation, properties, storage,
                    new ObjectMapper(), Duration.ofSeconds(30));
            preparesBothPrimaryPrefixesAndRecoversOnlyTheFailedTarget(properties, loader, topology, runner,
                    storage, initializer, primaryA, primaryB, replicaA, replicaB);
            var bootstrapper = new EgonColaShardingDataSourceBootstrapper(new EgonColaPhysicalDataSourceFactory(),
                    loader, topology, new OutboxLogicalDataSourceFactory(initializer));
            logical = bootstrapper.createDataSource(properties);
            new OutboxSchemaMetadataValidator(initializer, bootstrapper.fingerprint(), storage,
                    new ObjectMapper(), validation).validate();
            int slotB = bootstrapper.profiles().get("egon_cola_outbox_message").tenantSlotMap().entrySet().stream()
                    .filter(entry -> entry.getValue().equals("group_b")).map(Map.Entry::getKey).findFirst().orElseThrow();
            JdbcTemplate routed = new JdbcTemplate(logical);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(logical));
            String identity = mapperSql("selectExistingByIdentity"), advisory = mapperSql("tryAcquireTransactionLock");

            transaction.executeWithoutResult(status -> {
                routed.update("UPDATE " + businessA + " SET state = 'rolled-back' WHERE id = 1");
                insert(routed, 1, 0, "rollback", "rollback-key");
                assertThat(OutboxShardContext.withSlot(0, () -> routed.queryForMap(identity, 0, "rollback", "rollback-key")))
                        .containsEntry("id", 1L);
                status.setRollbackOnly();
            });
            assertThat(primaryA.queryForObject("SELECT state FROM public." + businessA + " WHERE id = 1", String.class))
                    .isEqualTo("initial");
            assertThat(primaryA.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Long.class)).isZero();

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                routed.update("UPDATE " + businessA + " SET state = 'must-rollback' WHERE id = 1");
                try {
                    insert(routed, 2, slotB, OutboxShardIdentity.forGroup("group_b", "group_a", "wrong"), null);
                } catch (org.springframework.dao.DataAccessException expected) {
                    assertThat(expected).hasStackTraceContaining("LOCAL_WRITE_TARGET_MISMATCH");
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(primaryA.queryForObject("SELECT state FROM public." + businessA + " WHERE id = 1", String.class))
                    .isEqualTo("initial");
            assertThat(primaryB.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Long.class)).isZero();

            transaction.executeWithoutResult(status -> {
                routed.update("UPDATE " + businessB + " SET state = 'committed' WHERE id = 1");
                insert(routed, 3, slotB, OutboxShardIdentity.forGroup("group_b", "group_a", "commit"), null);
                assertThat(routed.queryForObject("SELECT state FROM " + businessB + " WHERE id = 1", String.class))
                        .isEqualTo("initial");
            });
            assertThat(primaryB.queryForObject("SELECT state FROM public." + businessB + " WHERE id = 1", String.class))
                    .isEqualTo("committed");
            assertThat(primaryB.queryForObject("SELECT shard_slot FROM egon_outbox.egon_cola_outbox_message WHERE id = 3", Integer.class))
                    .isEqualTo(slotB);
            assertThatThrownBy(() -> insert(primaryB, 90, slotB, "unscoped-id", null))
                    .hasStackTraceContaining("ck_outbox_message_shard_identity");
            assertThatThrownBy(() -> insert(primaryB, 91, slotB,
                    OutboxShardIdentity.forGroup("group_b", "group_a", "valid"), "unscoped-key"))
                    .hasStackTraceContaining("ck_outbox_key_shard_identity");
            primaryB.update("UPDATE egon_outbox.egon_cola_outbox_message SET status = 'SUCCEEDED' WHERE id = 3");
            for (int index = 0; index < 20; index++) {
                insert(primaryA, 100 + index, 0, "a-" + index, null);
                insert(primaryB, 200 + index, slotB, OutboxShardIdentity.forGroup("group_b", "group_a", "b-" + index), null);
            }
            awaitRows(replicaA, 20);
            awaitRows(replicaB, 21);
            CyclicBarrier barrier = new CyclicBarrier(4);
            List<Long> claimed = new ArrayList<>();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<java.util.concurrent.Future<List<Long>>>();
                for (int worker = 0; worker < 4; worker++) {
                    int selectedSlot = worker % 2 == 0 ? 0 : slotB;
                    String owner = "worker-" + worker;
                    futures.add(executor.submit(() -> {
                        barrier.await();
                        return claimWithContentionRetries(transaction, routed, advisory, selectedSlot, owner, 10);
                    }));
                }
                for (var future : futures) { claimed.addAll(future.get(30, TimeUnit.SECONDS)); }
            }
            claimed.addAll(transaction.execute(status -> claim(routed, advisory, 0, "remaining-a", 20)));
            claimed.addAll(transaction.execute(status -> claim(routed, advisory, slotB, "remaining-b", 20)));
            assertThat(claimed).hasSize(40);
            assertThat(new HashSet<>(claimed)).hasSize(40);
        } finally {
            try {
                if (logical instanceof AutoCloseable closeable) { closeable.close(); }
            } finally {
                if (schemaA) { primaryA.execute("DROP SCHEMA egon_outbox CASCADE"); }
                if (schemaB) { primaryB.execute("DROP SCHEMA egon_outbox CASCADE"); }
                if (tableA) { primaryA.execute("DROP TABLE public." + businessA); }
                if (tableB) { primaryB.execute("DROP TABLE public." + businessB); }
            }
        }
    }

    private static void preparesBothPrimaryPrefixesAndRecoversOnlyTheFailedTarget(
            EgonColaShardingProperties properties, EgonColaShardingYamlLoader loader,
            EgonColaShardingTopologyValidator topology, EgonColaPostgreDdlRunner runner,
            OutboxMpStorageProperties storage, OutboxManagedDdlInitializer initializer,
            JdbcTemplate primaryA, JdbcTemplate primaryB, JdbcTemplate replicaA, JdbcTemplate replicaB) throws Exception {
        EgonColaDdlManifestBO manifest;
        try (InputStream input = OutboxPostgresqlMultishardIntegrationTest.class.getClassLoader()
                .getResourceAsStream("db/egon-outbox-mp/manifest.json")) {
            manifest = new ObjectMapper().readValue(input, EgonColaDdlManifestBO.class);
        }
        assertThat(manifest.scripts()).extracting(EgonColaDdlManifestBO.ScriptBO::version)
                .containsExactly("20260924_001", "20261027_001", "20261122_001");
        var previousTables = new LinkedHashMap<>(properties.getTables());
        previousTables.remove("egon_cola_outbox_queue_member");
        previousTables.remove("egon_cola_outbox_queue_lease");
        var previousProperties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                .configStyle(ConfigStyleEnum.STRATEGY).dataSources(properties.getDataSources()).tables(previousTables).build();
        String previousFingerprint = topology.validate(previousProperties, loader.load(previousProperties)).fingerprint();
        byte[] yaml = loader.load(properties);
        String fingerprint = topology.validate(properties, yaml).fingerprint();
        var previousManifest = new EgonColaDdlManifestBO(manifest.family(), manifest.scripts().subList(0, 2));
        runner.run(List.of(
                new EgonColaDdlTargetBO("a_primary", "egon_outbox", EgonColaDdlTargetBO.RoleEnum.MASTER_DATA,
                        primaryA.getDataSource(), previousManifest, previousFingerprint),
                new EgonColaDdlTargetBO("b_primary", "egon_outbox", EgonColaDdlTargetBO.RoleEnum.MASTER_DATA,
                        primaryB.getDataSource(), previousManifest, previousFingerprint)));
        storage.setPreviousRouteFingerprints(List.of(previousFingerprint));
        Map<String, DataSource> physical = Map.of("a_primary", primaryA.getDataSource(),
                "a_replica", replicaA.getDataSource(), "b_primary", primaryB.getDataSource(),
                "b_replica", replicaB.getDataSource());

        // A completes before B reaches the intentional SQL error; B's new script is one transaction.
        primaryB.execute("CREATE TABLE egon_outbox.egon_cola_outbox_queue_lease (failure_probe integer)");
        assertThatThrownBy(() -> initializer.initialize(physical, yaml))
                .hasStackTraceContaining("DDL_TARGET_FAILED: b_primary/egon_outbox");
        assertThat(initializer.isReadyFor(primaryA.getDataSource(), fingerprint)).isFalse();
        assertThat(initializer.isReadyFor(primaryB.getDataSource(), fingerprint)).isFalse();
        assertThat(primaryA.queryForObject("SELECT count(*) FROM egon_outbox.ddl_history", Integer.class)).isEqualTo(3);
        assertThat(primaryB.queryForObject("SELECT count(*) FROM egon_outbox.ddl_history", Integer.class)).isEqualTo(2);
        assertThat(primaryB.queryForObject("SELECT to_regclass('egon_outbox.egon_cola_outbox_queue_member') IS NULL",
                Boolean.class)).isTrue();
        primaryB.execute("DROP TABLE egon_outbox.egon_cola_outbox_queue_lease");

        initializer.initialize(physical, yaml);
        initializer.initialize(physical, yaml);
        for (JdbcTemplate physicalNode : List.of(primaryA, primaryB, replicaA, replicaB)) {
            assertThat(physicalNode.queryForObject("SELECT count(*) FROM egon_outbox.ddl_history", Integer.class)).isEqualTo(3);
            assertThat(physicalNode.queryForObject("SELECT count(*) FROM egon_outbox.ddl_history "
                    + "WHERE version IN ('20260924_001', '20261027_001') AND route_fingerprint = ?",
                    Integer.class, previousFingerprint)).isEqualTo(2);
            assertThat(physicalNode.queryForObject("SELECT count(*) FROM egon_outbox.ddl_history "
                    + "WHERE version = '20261122_001' AND route_fingerprint = ?",
                    Integer.class, fingerprint)).isEqualTo(1);
            assertThat(physicalNode.queryForObject("SELECT to_regclass('egon_outbox.egon_cola_outbox_queue_member') IS NOT NULL "
                    + "AND to_regclass('egon_outbox.egon_cola_outbox_queue_lease') IS NOT NULL", Boolean.class)).isTrue();
            for (var script : manifest.scripts()) {
                assertThat(physicalNode.queryForObject("SELECT checksum FROM egon_outbox.ddl_history WHERE version = ?",
                        String.class, script.version())).isEqualTo(script.sha256());
            }
        }
        for (var entry : Map.of("a_primary", primaryA, "b_primary", primaryB).entrySet()) {
            var oldTarget = new EgonColaDdlTargetBO(entry.getKey(), "egon_outbox",
                    EgonColaDdlTargetBO.RoleEnum.MASTER_DATA, entry.getValue().getDataSource(), previousManifest,
                    fingerprint, java.util.Set.of(previousFingerprint));
            assertThatThrownBy(() -> runner.run(List.of(oldTarget))).hasStackTraceContaining("MANIFEST_PREFIX_MISMATCH");
        }
    }

    private static List<Long> claim(JdbcTemplate jdbc, String advisory, int slot, String owner, int limit) {
        return OutboxShardContext.withSlot(slot, () -> claimScoped(jdbc, advisory, slot, owner, limit));
    }

    private static List<Long> claimWithContentionRetries(TransactionTemplate transaction, JdbcTemplate jdbc,
            String advisory, int slot, String owner, int limit) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> claim(jdbc, advisory, slot, owner, limit));
            } catch (org.springframework.dao.DataAccessException failure) {
                boolean lockUnavailable = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                        lockUnavailable = true;
                        break;
                    }
                }
                // NOWAIT aborts the physical transaction; retry only after TransactionTemplate rolled it back.
                if (!lockUnavailable || attempt >= 15) throw failure;
            }
        }
    }

    private static List<Long> claimScoped(JdbcTemplate jdbc, String advisory, int slot, String owner, int limit) {
        List<Long> candidates = jdbc.queryForList("SELECT id FROM egon_outbox.egon_cola_outbox_message "
                + "WHERE shard_slot = ? AND status = 'PENDING' ORDER BY id", Long.class, slot);
        List<Long> result = new ArrayList<>();
        for (long id : candidates) {
            if (result.size() == limit) { break; }
            List<Boolean> locked = jdbc.queryForList(advisory, Boolean.class, id, slot);
            if (locked.isEmpty() || !locked.getFirst()) { continue; }
            int changed = jdbc.update("UPDATE egon_outbox.egon_cola_outbox_message SET status = 'PROCESSING', "
                    + "locked_by = ?, version = version + 1 WHERE id = ? AND shard_slot = ? AND status = 'PENDING'",
                    owner, id, slot);
            if (changed == 1) { result.add(id); }
        }
        return result;
    }

    private static void insert(JdbcTemplate jdbc, long id, int slot, String messageId, String key) {
        OutboxShardContext.withSlot(slot, () -> jdbc.update("INSERT INTO egon_outbox.egon_cola_outbox_message "
                        + "(id, shard_slot, tenant_id, create_user_id, create_time, update_user_id, update_time, version, "
                        + "message_id, idempotency_key, message_fingerprint, channel, destination, payload, content_type, "
                        + "headers_json, status, attempt_count, max_attempts, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, 0, 'test', now(), 'test', now(), 0, ?, ?, ?, 'test', 'orders', '{}', "
                        + "'application/json', '{}', 'PENDING', 0, 3, now(), now(), now())",
                id, slot, messageId, key, "a".repeat(64)));
    }

    private static String mapperSql(String method) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxMessageDAO.class);
        String path = "mapper/outbox/OutboxMessageMapper.xml";
        try (InputStream input = OutboxPostgresqlMultishardIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(OutboxMessageDAO.class.getName() + '.' + method)
                .getBoundSql(Map.of("id", 1L, "owner", "worker", "messageId", "m", "idempotencyKey", "k")).getSql();
    }

    private static void awaitRows(JdbcTemplate replica, long count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (Long.valueOf(count).equals(replica.queryForObject(
                    "SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Long.class))) { return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Dedicated replica did not replay the outbox rows");
    }

    private static JdbcTemplate jdbc(String url, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role,
                                                       String url, String user, String password) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver", url, user, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Missing dedicated test setting: " + name); }
        return value;
    }
}
