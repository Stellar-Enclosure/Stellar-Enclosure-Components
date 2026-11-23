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
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.dispatch.OutboxDispatcher;
import top.egon.cola.component.outbox.store.MybatisPlusOutboxStore;
import top.egon.cola.component.outbox.store.OutboxFinishCommand;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit opt-in only: two dedicated, empty PostgreSQL primary/replica groups are required. */
@EnabledIfEnvironmentVariable(named = "EGON_OUTBOX_PG_MULTISHARD_IT", matches = "true")
class OutboxPostgresqlMultishardIntegrationTest {

    @Test
    void productionConsumersIsolatePrimaryFailureAndRestartLocallyWithoutRewindingHistory() throws Exception {
        String user = required("EGON_OUTBOX_PG_USER"), password = required("EGON_OUTBOX_PG_PASSWORD");
        JdbcTemplate primaryA = jdbc(required("EGON_OUTBOX_PG_SHARD_0_PRIMARY_URL"), user, password);
        JdbcTemplate primaryB = jdbc(required("EGON_OUTBOX_PG_SHARD_1_PRIMARY_URL"), user, password);
        JdbcTemplate replicaA = jdbc(required("EGON_OUTBOX_PG_SHARD_0_REPLICA_URL"), user, password);
        JdbcTemplate replicaB = jdbc(required("EGON_OUTBOX_PG_SHARD_1_REPLICA_URL"), user, password);
        String readProbe = "outbox_runtime_probe_"+UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean schemaA = false, schemaB = false, probeCreated = false, replayPaused = false;
        ConfigurableApplicationContext first = null, second = null, local = null;
        RuntimeProbe coordinatedProbe = new RuntimeProbe();
        RuntimeFault primaryFailure = new RuntimeFault();
        EgonColaModelValidationUtils.Binding anchor = null;
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            assertThat(EgonColaModelValidationUtils.current()).as("no other runtime fixture may be active").isNull();
            anchor = EgonColaModelValidationUtils.initialize(new ValidationUtils(validators.getValidator()), coordinatedProbe);
            try {
                primaryA.execute("CREATE SCHEMA egon_outbox");
                schemaA = true;
                primaryB.execute("CREATE SCHEMA egon_outbox");
                schemaB = true;
                primaryA.execute("CREATE TABLE public."+readProbe+" (id bigint PRIMARY KEY, state varchar(64) NOT NULL)");
                probeCreated = true;
                primaryA.update("INSERT INTO public."+readProbe+" VALUES (1, 'replica-before-pause')");
                first = runtimeContext("runtime-one", DispatchMode.COORDINATED, coordinatedProbe, primaryFailure, readProbe);
                second = runtimeContext("runtime-two", DispatchMode.COORDINATED, coordinatedProbe, primaryFailure, readProbe);
                assertRuntimeStore(first, DispatchMode.COORDINATED);
                assertRuntimeStore(second, DispatchMode.COORDINATED);
                int slotB = runtimeSlot(second, "group_b");
                assertThat(slotB).isPositive();
                awaitRuntime(() -> activeRuntimeMembers(primaryA) == 2 && activeRuntimeMembers(primaryB) == 2,
                        "both real consumers joined both primary memberships");
                System.out.println("MP_RUNTIME_GATE MPI-04: two production consumers joined both shards");

                // Both applications are ready before pausing this explicitly opted-in fixture replica.
                replayPaused = true;
                replicaA.execute("SELECT pg_wal_replay_pause()");
                awaitRuntime(() -> "paused".equals(replicaA.queryForObject("SELECT pg_get_wal_replay_pause_state()", String.class)),
                        "the fixture A replica paused replay");
                primaryA.update("UPDATE public."+readProbe+" SET state='primary-after-pause' WHERE id=1");
                JdbcTemplate ordinary = new JdbcTemplate(first.getBean("dataSource", DataSource.class));
                assertThat(ordinary.queryForObject("SELECT state FROM "+readProbe+" WHERE id=1", String.class))
                        .isEqualTo("replica-before-pause");
                System.out.println("MP_RUNTIME_GATE MPI-03: ordinary logical business read stayed on the lagging replica");

                String aMessage = enqueueRuntime(first, "group_a", "hold-before-b-outage");
                assertThat(coordinatedProbe.aStarted.await(10, TimeUnit.SECONDS)).as("real A handler started").isTrue();
                Map<String, Object> runningA = primaryA.queryForMap("SELECT id,status,attempt_count,locked_by,shard_slot "
                        + "FROM egon_outbox.egon_cola_outbox_message WHERE message_id=?", aMessage);
                assertThat(runningA).containsEntry("status", "PROCESSING").containsEntry("attempt_count", 1)
                        .containsEntry("shard_slot", 0);
                assertThat(runningA.get("locked_by")).isNotNull();
                assertThat(replicaA.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message "
                        + "WHERE message_id=?", Integer.class, aMessage)).isZero();
                System.out.println("MP_RUNTIME_GATE MPI-02/MPI-03: A real claim committed before handler start while replica lacks the message");

                // The failure begins only after A's actual claim committed and its real handler started.
                primaryFailure.unavailable.set(true);
                String bMessage = OutboxShardIdentity.forGroup("group_b", "group_a", "runtime-b-failed");
                insert(primaryB, ((Number) runningA.get("id")).longValue(), slotB, bMessage, null);
                awaitRuntime(() -> primaryFailure.failures.get() >= 2, "B retried through the failing physical endpoint");
                coordinatedProbe.releaseA.countDown();
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryA, aMessage)), "A ACK while B is unavailable");
                assertThat(primaryFailure.unavailable).isTrue();
                assertThat(replicaA.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message "
                        + "WHERE message_id=?", Integer.class, aMessage)).isZero();
                assertThat(ordinary.queryForObject("SELECT state FROM "+readProbe+" WHERE id=1", String.class))
                        .isEqualTo("replica-before-pause");
                System.out.println("MP_RUNTIME_GATE MPI-03: A primary ACK completed with paused replica and repeated B connection failures");
                assertThat(primaryB.queryForMap("SELECT status,attempt_count,locked_by FROM "
                        + "egon_outbox.egon_cola_outbox_message WHERE message_id=?", bMessage))
                        .containsEntry("status", "PENDING").containsEntry("attempt_count", 0).containsEntry("locked_by", null);
                assertThat(coordinatedProbe.starts.getOrDefault(bMessage, new AtomicInteger()).get()).isZero();

                replicaA.execute("SELECT pg_wal_replay_resume()");
                replayPaused = false;
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(replicaA, aMessage)), "A replica caught up after resume");
                primaryFailure.unavailable.set(false);
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryB, bMessage)), "B recovers with its original message identity");
                assertThat(coordinatedProbe.starts.get(aMessage).get()).isEqualTo(1);
                assertThat(coordinatedProbe.starts.get(bMessage).get()).isEqualTo(1);
                System.out.println("MP_RUNTIME_GATE MPI-02/MPI-03: B recovered; equal physical ids stayed isolated by their actual shard slots");
                assertThat(primaryA.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message "
                        + "WHERE message_id=?", Integer.class, bMessage)).isZero();
                assertThat(primaryB.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message "
                        + "WHERE message_id=?", Integer.class, aMessage)).isZero();

                primaryFailure.failAfterCommit.set(FailedCommit.ACK);
                String uncertainAck = enqueueRuntime(second, "group_b", "ack-commit-unknown");
                awaitRuntime(() -> primaryFailure.unknownCommits.get() == 1
                        && "SUCCEEDED".equals(runtimeStatus(primaryB, uncertainAck)), "B ACK committed but confirmation was lost");
                awaitRuntime(() -> primaryFailure.confirmationReads.get() > 0, "B re-read ACK from the primary");
                assertThat(coordinatedProbe.starts.get(uncertainAck).get()).isEqualTo(1);

                primaryFailure.failAfterCommit.set(FailedCommit.CLAIM);
                String uncertainClaim = enqueueRuntime(second, "group_b", "claim-commit-unknown");
                awaitRuntime(() -> primaryFailure.unknownCommits.get() == 2
                        && "PROCESSING".equals(runtimeStatus(primaryB, uncertainClaim)), "B claim committed but confirmation was lost");
                assertThat(coordinatedProbe.starts).doesNotContainKey(uncertainClaim);
                var priorClaim = primaryB.queryForMap("SELECT id,locked_by,attempt_count FROM "
                        + "egon_outbox.egon_cola_outbox_message WHERE message_id=?", uncertainClaim);
                assertThat(priorClaim).containsEntry("attempt_count", 1);
                System.out.println("MP_RUNTIME_GATE MPI-05: B unknown ACK was reread; B unknown claim triggered no handler");

                second.close();
                second = null;
                first.close();
                first = null;
                int drainedStarts = coordinatedProbe.totalStarts.get();
                var historyA = runtimeHistory(primaryA);
                var historyB = runtimeHistory(primaryB);
                var epochsA = runtimeEpochs(primaryA);
                var epochsB = runtimeEpochs(primaryB);
                assertThat(historyA).hasSize(3);
                assertThat(historyB).hasSize(3);
                assertThat(epochsA).isNotEmpty();
                assertThat(epochsB).isNotEmpty();

                RuntimeProbe pausedProbe = new RuntimeProbe();
                String pausedMessage;
                try (ConfigurableApplicationContext paused = runtimeContext(
                        "runtime-paused", DispatchMode.COORDINATED, pausedProbe, new RuntimeFault(), readProbe, false)) {
                    pausedMessage = enqueueRuntime(paused, "group_a", "during-consumption-pause");
                    assertThat(runtimeStatus(primaryA, pausedMessage)).isEqualTo("PENDING");
                    awaitRuntime(() -> activeRuntimeMembers(primaryA) == 0 && activeRuntimeMembers(primaryB) == 0,
                            "former coordinators retired or expired while the paused producer wrote no heartbeat");
                    assertThat(pausedProbe.totalStarts.get()).isZero();
                }

                // Set an already committed fixture claim's deadline in the past to exercise recovery without a wall-clock sleep.
                assertThat(primaryB.update("UPDATE egon_outbox.egon_cola_outbox_message "
                        + "SET locked_until=clock_timestamp()-interval '1 second' WHERE message_id=? AND status='PROCESSING'",
                        uncertainClaim)).isEqualTo(1);
                RuntimeProbe localProbe = new RuntimeProbe();
                local = runtimeContext("runtime-local", DispatchMode.LOCAL, localProbe, new RuntimeFault(), readProbe);
                assertRuntimeStore(local, DispatchMode.LOCAL);
                String localA = enqueueRuntime(local, "group_a", "local-a");
                String localB = enqueueRuntime(local, "group_b", "local-b");
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryA, localA)), "same-artifact LOCAL A delivery");
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryB, localB)), "same-artifact LOCAL B delivery");
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryA, pausedMessage)), "LOCAL delivers producer-only row");
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(primaryB, uncertainClaim)), "LOCAL reclaims old expired message");
                assertThat(primaryB.queryForMap("SELECT attempt_count,locked_by FROM egon_outbox.egon_cola_outbox_message "
                        + "WHERE message_id=?", uncertainClaim)).containsEntry("attempt_count", 2).containsEntry("locked_by", null);
                assertThat(localProbe.starts.get(uncertainClaim).get()).isEqualTo(1);
                assertThat(local.getBean(OutboxStore.class).finishDelivery(OutboxFinishCommand.builder()
                        .id(((Number) priorClaim.get("id")).longValue()).shardSlot(slotB).messageId(uncertainClaim)
                        .attemptCount(1).claimToken((String) priorClaim.get("locked_by"))
                        .deliveryResult(DeliveryResult.success()).build())).isEqualTo(OutboxFinishStatusEnum.LEASE_LOST);
                awaitRuntime(() -> "SUCCEEDED".equals(runtimeStatus(replicaA, localA))
                        && "SUCCEEDED".equals(runtimeStatus(replicaB, localB)), "both replicas replayed same-artifact LOCAL results");
                assertThat(localProbe.starts.get(pausedMessage).get()).isEqualTo(1);
                assertThat(localProbe.starts.get(localA).get()).isEqualTo(1);
                assertThat(localProbe.starts.get(localB).get()).isEqualTo(1);
                assertThat(coordinatedProbe.totalStarts.get()).as("all former coordinated consumers drained").isEqualTo(drainedStarts);
                assertThat(runtimeHistory(primaryA)).isEqualTo(historyA);
                assertThat(runtimeHistory(primaryB)).isEqualTo(historyB);
                assertThat(runtimeEpochs(primaryA)).isEqualTo(epochsA);
                assertThat(runtimeEpochs(primaryB)).isEqualTo(epochsB);
                System.out.println("MP_RUNTIME_GATE MPI-06: same-artifact LOCAL A/B, paused producer, and expired claim recovered; history and epochs unchanged");
            } finally {
                primaryFailure.unavailable.set(false);
                coordinatedProbe.releaseA.countDown();
                try {
                    if (replayPaused) replicaA.execute("SELECT pg_wal_replay_resume()");
                } finally {
                    try {
                        closeRuntimeContexts(local, second, first);
                    } finally {
                        try {
                            if (schemaA) primaryA.execute("DROP SCHEMA egon_outbox CASCADE");
                        } finally {
                            try {
                                if (schemaB) primaryB.execute("DROP SCHEMA egon_outbox CASCADE");
                            } finally {
                                try {
                                    if (probeCreated) primaryA.execute("DROP TABLE public."+readProbe);
                                } finally {
                                    if (anchor != null) anchor.close();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static ConfigurableApplicationContext runtimeContext(String node, DispatchMode mode,
            RuntimeProbe probe, RuntimeFault fault, String readProbe) {
        return runtimeContext(node, mode, probe, fault, readProbe, true);
    }

    private static ConfigurableApplicationContext runtimeContext(String node, DispatchMode mode,
            RuntimeProbe probe, RuntimeFault fault, String readProbe, boolean consumptionEnabled) {
        Map<String, Object> properties = runtimeProperties(node, mode, readProbe);
        properties.put("egon.cola.component.transactional-outbox.dispatch.consumption-enabled", consumptionEnabled);
        return new SpringApplicationBuilder(RuntimeApplication.class).web(WebApplicationType.NONE)
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("runtimeTestDeliveryHandler", new RuntimeDeliveryHandler(probe));
                    context.addBeanFactoryPostProcessor(beanFactory -> {
                        var definition = (AbstractBeanDefinition) beanFactory.getBeanDefinition("egonColaPhysicalDataSourceFactory");
                        definition.setInstanceSupplier(() -> new RuntimePhysicalDataSources(fault));
                    });
                })
                .run(properties.entrySet().stream().map(entry -> "--"+entry.getKey()+"="+entry.getValue()).toArray(String[]::new));
    }

    private static Map<String, Object> runtimeProperties(String node, DispatchMode mode, String readProbe) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.lazy-initialization", false);
        properties.put("spring.application.name", "outbox-mp-runtime-it");
        properties.put("egon.cola.component.cache.enabled", false);
        properties.put("egon.cola.component.id.machine-id", 733);
        properties.put("egon.cola.component.id.max-clock-backward", "5ms");
        properties.put("egon.cola.component.id.redis.enabled", false);
        properties.put("egon.cola.component.id.redis.require-lease", false);
        properties.put("mybatis-plus.mapper-locations", "classpath*:/mapper/**/*.xml");
        String mp = "egon.cola.component.mybatis-plus.";
        for (int index = 0; index < 3; index++) {
            properties.put(mp+"tenant-id.ignored-tables["+index+"]", List.of("egon_cola_outbox_message",
                    "egon_cola_outbox_queue_member", "egon_cola_outbox_queue_lease").get(index));
        }
        String sharding = mp+"sharding.";
        properties.put(sharding+"enabled", true);
        properties.put(sharding+"mode", "SHARDING_READWRITE");
        properties.put(sharding+"config-style", "STRATEGY");
        properties.put(sharding+"transaction-default-type", "LOCAL");
        String[] names = {"a_primary", "a_replica", "b_primary", "b_replica"};
        String[] urls = {"EGON_OUTBOX_PG_SHARD_0_PRIMARY_URL", "EGON_OUTBOX_PG_SHARD_0_REPLICA_URL",
                "EGON_OUTBOX_PG_SHARD_1_PRIMARY_URL", "EGON_OUTBOX_PG_SHARD_1_REPLICA_URL"};
        for (int index = 0; index < names.length; index++) {
            String key = sharding+"data-sources["+index+"].";
            properties.put(key+"name", names[index]);
            properties.put(key+"logical-name", index < 2 ? "group_a" : "group_b");
            properties.put(key+"role", index%2 == 0 ? "PRIMARY" : "REPLICA");
            properties.put(key+"driver-class-name", "org.postgresql.Driver");
            properties.put(key+"jdbc-url", required(urls[index]));
            properties.put(key+"username", required("EGON_OUTBOX_PG_USER"));
            properties.put(key+"password", required("EGON_OUTBOX_PG_PASSWORD"));
        }
        for (String table : List.of("egon_cola_outbox_message", "egon_cola_outbox_queue_member", "egon_cola_outbox_queue_lease")) {
            String key = sharding+"tables["+table+"].";
            properties.put(key+"type", "TECHNICAL_GROUP");
            properties.put(key+"data-source", "group_a");
            properties.put(key+"sharding-column", "shard_slot");
            properties.put(key+"schema", "egon_outbox");
            properties.put(key+"shard-groups[0]", "group_a");
            properties.put(key+"shard-groups[1]", "group_b");
        }
        properties.put(sharding+"tables[ddl_history].type", "SINGLE");
        properties.put(sharding+"tables[ddl_history].data-source", "group_a");
        properties.put(sharding+"tables[ddl_history].schema", "egon_outbox");
        properties.put(sharding+"tables["+readProbe+"].type", "SINGLE");
        properties.put(sharding+"tables["+readProbe+"].data-source", "group_a");
        properties.put(sharding+"tables["+readProbe+"].schema", "public");
        String outbox = "egon.cola.component.transactional-outbox.";
        properties.put(outbox+"enabled", true);
        properties.put(outbox+"node-id", node);
        properties.put(outbox+"storage.type", "MYBATIS_PLUS");
        properties.put(outbox+"storage.data-source-bean-name", "dataSource");
        properties.put(outbox+"storage.transaction-manager-bean-name", "transactionManager");
        properties.put(outbox+"storage.mp.sql-session-factory-bean-name", "sqlSessionFactory");
        properties.put(outbox+"storage.mp.auto-initialize-ddl", true);
        properties.put(outbox+"storage.validate-schema", true);
        properties.put(outbox+"annotation.enabled", false);
        properties.put(outbox+"dispatch.mode", mode.name());
        properties.put(outbox+"dispatch.max-claim-batch", 1);
        properties.put(outbox+"dispatch.max-concurrent-shards", 2);
        properties.put(outbox+"dispatch.ack-reserve", "1s");
        properties.put(outbox+"polling.enabled", true);
        properties.put(outbox+"polling.fixed-delay", "100ms");
        properties.put(outbox+"polling.jitter", 0.2);
        properties.put(outbox+"polling.batch-size", 2);
        properties.put(outbox+"polling.concurrency", 2);
        properties.put(outbox+"delivery.timeout", "15s");
        properties.put(outbox+"delivery.lease-duration", "30s");
        properties.put(outbox+"delivery.queue-capacity", 1);
        properties.put(outbox+"coordination.heartbeat-interval", "1s");
        properties.put(outbox+"coordination.claim-timeout", "750ms");
        properties.put(outbox+"state-machine.execution-timeout", "500ms");
        properties.put(outbox+"coordination.lease-duration", "4s");
        properties.put(outbox+"coordination.membership-ttl", "6s");
        properties.put(outbox+"coordination.member-retention", "1m");
        properties.put(outbox+"retry.recover-exhausted", false);
        properties.put(outbox+"cleanup.enabled", false);
        properties.put(outbox+"shutdown.grace-period", "5s");
        return properties;
    }

    private static void assertRuntimeStore(ConfigurableApplicationContext context, DispatchMode mode) {
        assertThat(context.getBean(OutboxStore.class)).isInstanceOf(MybatisPlusOutboxStore.class);
        assertThat(context.getBean(OutboxStore.class).supportsDispatchMode(mode)).isTrue();
        assertThat(context.getBean(OutboxDispatcher.class)).isNotNull();
        var factory = context.getBean("sqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class);
        assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(context.getBean("dataSource"));
        assertThat(factory.getConfiguration().getEnvironment().getTransactionFactory())
                .isInstanceOf(org.mybatis.spring.transaction.SpringManagedTransactionFactory.class);
    }

    private static int runtimeSlot(ConfigurableApplicationContext context, String group) {
        var bootstrapper = context.getBean(EgonColaShardingDataSourceBootstrapper.class);
        return bootstrapper.profiles().get("egon_cola_outbox_message").tenantSlotMap().entrySet().stream()
                .filter(entry -> entry.getValue().equals(group)).mapToInt(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static String enqueueRuntime(ConfigurableApplicationContext context, String group, String destination) {
        var transaction = new TransactionTemplate(context.getBean("transactionManager", PlatformTransactionManager.class));
        return transaction.execute(status -> context.getBean(TransactionalOutbox.class).enqueue(OutboxMessage.builder()
                .channel("test").shardGroup(group).destination(destination).payload(Map.of("phase", destination)).build()).messageId());
    }

    private static String runtimeStatus(JdbcTemplate oracle, String message) {
        return oracle.queryForObject("SELECT status FROM egon_outbox.egon_cola_outbox_message WHERE message_id=?", String.class, message);
    }

    private static int activeRuntimeMembers(JdbcTemplate oracle) {
        return oracle.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_queue_member "
                + "WHERE expires_at>clock_timestamp()", Integer.class);
    }

    private static void closeRuntimeContexts(ConfigurableApplicationContext... contexts) {
        RuntimeException first = null;
        for (var context : contexts) {
            if (context == null) continue;
            try { context.close(); }
            catch (RuntimeException failure) {
                if (first == null) first = failure; else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    private static List<Map<String, Object>> runtimeHistory(JdbcTemplate oracle) {
        return oracle.queryForList("SELECT script,type,version,checksum,route_fingerprint,installed_on,execution_ms "
                + "FROM egon_outbox.ddl_history ORDER BY version");
    }

    private static List<Map<String, Object>> runtimeEpochs(JdbcTemplate oracle) {
        return oracle.queryForList("SELECT shard_slot,channel,epoch FROM egon_outbox.egon_cola_outbox_queue_lease ORDER BY shard_slot,channel");
    }

    private static void awaitRuntime(BooleanSupplier condition, String phase) throws InterruptedException {
        long deadline = System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + phase);
    }

    private static final class RuntimeProbe {
        final CountDownLatch aStarted = new CountDownLatch(1);
        final CountDownLatch releaseA = new CountDownLatch(1);
        final Map<String, AtomicInteger> starts = new ConcurrentHashMap<>();
        final AtomicInteger totalStarts = new AtomicInteger();
    }

    private record RuntimeDeliveryHandler(RuntimeProbe probe) implements DeliveryHandler {
        public String channel() { return "test"; }
        public void validateDestination(String destination) { }
        public DeliveryResult deliver(DeliveryContext context) throws Exception {
            probe.starts.computeIfAbsent(context.messageId(), ignored -> new AtomicInteger()).incrementAndGet();
            probe.totalStarts.incrementAndGet();
            if (context.destination().equals("hold-before-b-outage")) {
                probe.aStarted.countDown();
                if (!probe.releaseA.await(12, TimeUnit.SECONDS)) throw new AssertionError("A handler release was not signalled");
            }
            return DeliveryResult.success();
        }
    }

    private enum FailedCommit { CLAIM, ACK }

    private static final class RuntimeFault {
        final AtomicBoolean unavailable = new AtomicBoolean();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<FailedCommit> failAfterCommit = new AtomicReference<>();
        final AtomicInteger unknownCommits = new AtomicInteger();
        final AtomicInteger confirmationReads = new AtomicInteger();
        void check() throws SQLException {
            if (unavailable.get()) {
                failures.incrementAndGet();
                throw new SQLTransientConnectionException("Test-owned primary B unavailable", "08006");
            }
        }
    }

    private static final class RuntimePhysicalDataSources extends EgonColaPhysicalDataSourceFactory {
        private final RuntimeFault fault;
        private RuntimePhysicalDataSources(RuntimeFault fault) { this.fault = fault; }
        @Override public Map<String, DataSource> create(EgonColaShardingProperties properties) {
            Map<String, DataSource> pools = new LinkedHashMap<>(super.create(properties));
            pools.put("b_primary", new RuntimeFailingDataSource(pools.get("b_primary"), fault));
            return java.util.Collections.unmodifiableMap(pools);
        }
    }

    private static final class RuntimeFailingDataSource extends DelegatingDataSource implements AutoCloseable {
        private final RuntimeFault fault;
        private RuntimeFailingDataSource(DataSource target, RuntimeFault fault) { super(target); this.fault = fault; }
        @Override public Connection getConnection() throws SQLException {
            fault.check();
            return guarded(super.getConnection());
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            fault.check();
            return guarded(super.getConnection(user, password));
        }
        private Connection guarded(Connection target) {
            AtomicReference<FailedCommit> pending = new AtomicReference<>();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String operation = method.getName();
                        if (operation.equals("commit")) {
                            fault.check();
                            Object result = invokeRuntime(target, method, args);
                            FailedCommit written = pending.getAndSet(null);
                            if (written != null && fault.failAfterCommit.compareAndSet(written, null)) {
                                fault.unknownCommits.incrementAndGet();
                                throw new SQLTransientConnectionException("Test-owned commit acknowledgement lost", "08006");
                            }
                            return result;
                        }
                        if (operation.equals("rollback")) pending.set(null);
                        Object result = invokeRuntime(target, method, args);
                        if (result instanceof Statement statement) {
                            Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            String sql = args != null && args.length > 0 && args[0] instanceof String query
                                    ? query.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT) : "";
                            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (ignored, call, parameters) -> {
                                if (!call.getName().startsWith("execute")) {
                                    return invokeRuntime(statement, call, parameters);
                                }
                                fault.check();
                                Object answer = invokeRuntime(statement, call, parameters);
                                if (sql.startsWith("update") && statement.getUpdateCount() > 0) {
                                    if (sql.contains("attempt_count = attempt_count + 1")) {
                                        pending.set(FailedCommit.CLAIM);
                                    } else if (sql.contains("locked_by = null") && sql.contains("completed_at =")) {
                                        pending.set(FailedCommit.ACK);
                                    }
                                }
                                if (fault.unknownCommits.get() == 1 && sql.startsWith("select")
                                        && sql.contains("where id =") && sql.endsWith("for update")) {
                                    fault.confirmationReads.incrementAndGet();
                                }
                                return answer;
                            });
                        }
                        return result;
                    });
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private static Object invokeRuntime(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName="top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheAutoConfiguration")
    static class RuntimeApplication { }

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
