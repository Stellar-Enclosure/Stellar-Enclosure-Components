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
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.migration.OutboxLogicalDataSourceFactory;
import top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer;
import top.egon.cola.component.outbox.persistence.OutboxSchemaMetadataValidator;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in only: the URLs must refer to one dedicated PostgreSQL primary/replica pair. */
@EnabledIfEnvironmentVariable(named = "EGON_OUTBOX_PG_READWRITE_IT", matches = "true")
class OutboxPostgresqlReadwriteIntegrationTest {

    @Test
    void outboxTechnicalLocksShareTheBusinessTransactionWhileBusinessReadsUseTheReplica() throws Exception {
        String primaryUrl = required("EGON_OUTBOX_PG_PRIMARY_URL");
        String replicaUrl = required("EGON_OUTBOX_PG_REPLICA_URL");
        String user = required("EGON_OUTBOX_PG_USER");
        String password = required("EGON_OUTBOX_PG_PASSWORD");
        JdbcTemplate primary = new JdbcTemplate(new DriverManagerDataSource(primaryUrl, user, password));
        JdbcTemplate replica = new JdbcTemplate(new DriverManagerDataSource(replicaUrl, user, password));
        String business = "outbox_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataSource logical = null;
        boolean schemaCreated = false;
        boolean businessCreated = false;
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            // CREATE without IF NOT EXISTS prevents this test from touching an existing outbox schema.
            primary.execute("CREATE SCHEMA egon_outbox");
            schemaCreated = true;
            primary.execute("CREATE TABLE public." + business + " (id bigint PRIMARY KEY, state varchar(50) NOT NULL)");
            businessCreated = true;
            primary.update("INSERT INTO public." + business + " VALUES (1, 'replica-visible')");

            ValidationUtils validation = new ValidationUtils(validators.getValidator());
            TableProperties outboxTable = new TableProperties("SINGLE", "group0", null, null, null, "egon_outbox");
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY)
                    .dataSources(List.of(source("primary", DataSourceRoleEnum.PRIMARY, primaryUrl, user, password),
                            source("replica", DataSourceRoleEnum.REPLICA, replicaUrl, user, password)))
                    .tables(Map.of("egon_cola_outbox_message", outboxTable, "ddl_history", outboxTable,
                            business, new TableProperties("SINGLE", "group0", null, null, null))).build();
            var strategy = new EgonColaShardingStrategyFactory(validation,
                    Map.of("SINGLE", new EgonColaSingleTableShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(strategy, new DefaultResourceLoader(), new StandardEnvironment());
            var topology = new EgonColaShardingTopologyValidator(validation);
            var runner = new EgonColaPostgreDdlRunner(validation, new PathMatchingResourcePatternResolver(),
                    Clock.systemUTC(), Duration.ofSeconds(10), Duration.ofSeconds(30));
            var storage = new OutboxMpStorageProperties();
            var initializer = new OutboxManagedDdlInitializer(runner, topology, validation, properties, storage,
                    new ObjectMapper(), Duration.ofSeconds(30));
            var bootstrapper = new EgonColaShardingDataSourceBootstrapper(new EgonColaPhysicalDataSourceFactory(),
                    loader, topology, new OutboxLogicalDataSourceFactory(initializer));
            logical = bootstrapper.createDataSource(properties);
            new OutboxSchemaMetadataValidator(initializer, bootstrapper.fingerprint(), storage,
                    new ObjectMapper(), validation).validate();

            JdbcTemplate jdbc = new JdbcTemplate(logical);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(logical));
            String identity = mapperSql("selectExistingByIdentity");
            String processing = mapperSql("selectProcessingForUpdate");
            String advisory = mapperSql("tryAcquireTransactionLock");

            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE " + business + " SET state = 'uncommitted' WHERE id = 1");
                insertMessage(jdbc, 101L, "rollback", "rollback-key");
                assertThat(jdbc.queryForObject("SELECT state FROM " + business + " WHERE id = 1", String.class))
                        .isEqualTo("replica-visible");
                assertThat(jdbc.queryForMap(identity, 0, "rollback", "rollback-key")).containsEntry("id", 101L);
                status.setRollbackOnly();
            });
            assertThat(primary.queryForObject("SELECT state FROM public." + business + " WHERE id = 1", String.class))
                    .isEqualTo("replica-visible");
            assertThat(primary.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Long.class))
                    .isZero();

            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE " + business + " SET state = 'committed' WHERE id = 1");
                insertMessage(jdbc, 102L, "commit", "commit-key");
                jdbc.update("UPDATE egon_outbox.egon_cola_outbox_message SET status = 'PROCESSING', "
                        + "locked_by = 'worker', locked_until = now() + interval '1 minute', version = version + 1 WHERE id = ?", 102L);
                assertThat(jdbc.queryForMap(processing, 102L, 0, "worker")).containsEntry("status", "PROCESSING");
                assertThat(jdbc.queryForObject(advisory, Boolean.class, 102L, 0)).isTrue();
                assertThat(jdbc.queryForObject("SELECT state FROM " + business + " WHERE id = 1", String.class))
                        .isEqualTo("replica-visible");
            });
            assertThat(primary.queryForObject("SELECT state FROM public." + business + " WHERE id = 1", String.class))
                    .isEqualTo("committed");
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT state FROM " + business + " WHERE id = 1 FOR UPDATE", String.class))
                    .hasStackTraceContaining("READWRITE_SQL_ROLE_MISMATCH");
            awaitReplica(replica);
            assertThat(jdbc.queryForObject("SELECT message_id FROM egon_outbox.egon_cola_outbox_message WHERE id = 102", String.class))
                    .isEqualTo("commit");
            try (var executor = Executors.newSingleThreadExecutor()) {
                CountDownLatch acquired = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                var holder = executor.submit(() -> transaction.execute(status -> {
                    assertThat(jdbc.queryForObject(advisory, Boolean.class, 102L, 0)).isTrue();
                    acquired.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release outbox lock");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    return true;
                }));
                try {
                    assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                    transaction.executeWithoutResult(status -> assertThat(jdbc.queryForList(advisory, 102L, 0)).isEmpty());
                } finally {
                    release.countDown();
                }
                assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            try {
                if (logical instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } finally {
                if (schemaCreated) {
                    primary.execute("DROP SCHEMA egon_outbox CASCADE");
                }
                if (businessCreated) {
                    primary.execute("DROP TABLE public." + business);
                }
            }
        }
    }

    private static void insertMessage(JdbcTemplate jdbc, long id, String messageId, String key) {
        jdbc.update("INSERT INTO egon_outbox.egon_cola_outbox_message "
                        + "(id, tenant_id, create_user_id, create_time, update_user_id, update_time, version, "
                        + "message_id, idempotency_key, message_fingerprint, channel, destination, payload, content_type, "
                        + "headers_json, status, attempt_count, max_attempts, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, 0, 'test', now(), 'test', now(), 0, ?, ?, ?, 'test', 'orders', '{}', "
                        + "'application/json', '{}', 'PENDING', 0, 3, now(), now(), now())",
                id, messageId, key, "a".repeat(64));
    }

    private static String mapperSql(String method) {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxMessageDAO.class);
        String path = "mapper/outbox/OutboxMessageMapper.xml";
        try (InputStream input = OutboxPostgresqlReadwriteIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            return configuration.getMappedStatement(OutboxMessageDAO.class.getName() + '.' + method)
                    .getBoundSql(Map.of("id", 1L, "owner", "worker", "messageId", "message", "idempotencyKey", "key"))
                    .getSql();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to load outbox mapper SQL", failure);
        }
    }

    private static void awaitReplica(JdbcTemplate replica) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(replica.queryForObject("SELECT pg_is_in_recovery()", Boolean.class))
                    && Long.valueOf(1L).equals(replica.queryForObject(
                    "SELECT count(*) FROM egon_outbox.egon_cola_outbox_message WHERE id = 102", Long.class))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Dedicated replica did not replay the committed outbox row");
    }

    private static PhysicalDataSourceProperties source(String name, DataSourceRoleEnum role, String url,
                                                       String user, String password) {
        return new PhysicalDataSourceProperties(name, "group0", role, "org.postgresql.Driver", url, user, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing dedicated test setting: " + name);
        }
        return value;
    }
}
