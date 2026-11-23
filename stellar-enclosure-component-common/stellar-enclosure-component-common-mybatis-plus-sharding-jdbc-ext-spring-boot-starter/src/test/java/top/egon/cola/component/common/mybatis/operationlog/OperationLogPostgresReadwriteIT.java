package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.routing.EgonColaWriteTargetResolver;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaPhysicalDataSourceFactory;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingDataSourceBootstrapper;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaComplexTenantThenBusinessShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Requires two disposable PostgreSQL replication groups. */
@EnabledIfEnvironmentVariable(named = "EGON_MP_PG_SHARDING_IT", matches = "true")
class OperationLogPostgresReadwriteIT {

    @Test
    void verifiesPhysicalRoutesReplicaReplayAndAtomicReceipt() throws Exception {
        for (int slot = 0; slot < 2; slot++) {
            verifyRole(url(slot, "PRIMARY"), false);
            verifyRole(url(slot, "REPLICA"), true);
            assertThat(replicationIdentity(url(slot, "REPLICA")))
                    .isEqualTo(replicationIdentity(url(slot, "PRIMARY")));
        }
        var settings = new ArrayList<>(List.of(
                "egon.cola.component.mybatis-plus.operation-log.target.enabled=true",
                "egon.cola.component.mybatis-plus.operation-log.target.ddl.enabled=true",
                "egon.cola.component.mybatis-plus.operation-log.target.ddl.auto-migrate=true",
                "egon.cola.component.mybatis-plus.operation-log.target.max-age=P90D",
                "egon.cola.component.mybatis-plus.operation-log.target.transaction-timeout=15s",
                "egon.cola.component.cache.enabled=false"));
        for (int slot = 0; slot < 2; slot++) {
            for (int role = 0; role < 2; role++) {
                String roleCode = role == 0 ? "PRIMARY" : "REPLICA";
                String key = "egon.cola.component.mybatis-plus.operation-log.target.data-sources[" + (slot * 2 + role) + "].";
                settings.addAll(List.of(key + "name=audit_" + slot + "_" + role,
                        key + "logical-name=audit_" + slot, key + "role=" + roleCode,
                        key + "driver-class-name=org.postgresql.Driver", key + "jdbc-url=" + url(slot, roleCode),
                        key + "username=" + required("EGON_MP_PG_USER"), key + "password=" + required("EGON_MP_PG_PASSWORD")));
            }
        }
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EgonColaMybatisPlusAutoConfiguration.class,
                        EgonOperationLogAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withUserConfiguration(Host.class).withPropertyValues(settings.toArray(String[]::new)).run(context -> {
                    assertThat(context).hasNotFailed();
                    var source = context.getBean("dataSource", DataSource.class);
                    var target = context.getBean("operationLogDataSource", DataSource.class);
                    assertThat(target).isNotSameAs(source);
                    assertThat(context.getBean(DataSourceTransactionManager.class).getDataSource()).isSameAs(source);
                    assertThat(context.getBean("operationLogSqlSessionFactory", SqlSessionFactory.class)
                            .getConfiguration().getEnvironment().getDataSource()).isSameAs(target);

                    var repo = context.getBean("operationLogRepository", OperationLogRepository.class);
                    var bootstrap = context.getBean("operationLogTargetBootstrapper", EgonColaShardingDataSourceBootstrapper.class);
                    var sourceTransactions = context.getBean("transactionManager", DataSourceTransactionManager.class);
                    var sourceTransaction = new org.springframework.transaction.support.TransactionTemplate(sourceTransactions);
                    var targetPolicy = new EgonOperationLogAutoConfiguration.TargetSqlPolicy();
                    var profile = bootstrap.profiles().get(OperationLogRepository.FACT_TABLE);
                    var route = context.getBean(EgonColaTwoLevelRouteStrategy.class);
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    assertThatCode(() -> targetPolicy.validateLogicalSql("select * from egon_cola_operation_log"))
                            .doesNotThrowAnyException();
                    assertThatThrownBy(() -> targetPolicy.validateLogicalSql("delete from egon_cola_operation_log"))
                            .hasMessage("OPERATION_LOG_IMMUTABLE");
                    long base = Math.max(1, System.nanoTime());
                    for (int slot = 0; slot < 2; slot++) {
                        long tenant = 1;
                        while (!node(route, profile, tenant, base).group().equals("audit_" + slot)) {
                            tenant++;
                        }
                        for (int bucket = 0; bucket < 2; bucket++) {
                            String table = OperationLogRepository.FACT_TABLE + "_t" + slot + "_b" + bucket;
                            long id = base++;
                            while (!node(route, profile, tenant, id).table().equals(table)) {
                                id++;
                            }
                            base = Math.max(base, id + 1);
                            var event = event(tenant, id, now);
                            try (var ignored = MDC.putCloseable("tenantId", Long.toString(tenant));
                                 var actor = MDC.putCloseable("userId", "test-authority")) {
                                assertThatThrownBy(() -> sourceTransaction.executeWithoutResult(status -> repo.accept(event)))
                                        .hasMessage("OPERATION_LOG_SOURCE_TRANSACTION_ACTIVE");
                                repo.accept(event);
                                repo.accept(event);
                                assertThatThrownBy(() -> repo.accept(event.toBuilder()
                                        .occurredAt(now.plus(33, ChronoUnit.DAYS)).build()))
                                        .isInstanceOf(OperationLogRepository.PermanentRejectionException.class);
                                assertThat(physicalCount(url(slot, "PRIMARY"), table, id)).isEqualTo(1);
                                awaitReplay(url(slot, "REPLICA"), table, id);
                                assertThat(repo.findDetail(tenant, id, now)).isPresent().get()
                                        .satisfies(row -> {
                                            assertThat(row.getOccurredAt()).isEqualTo(now);
                                            assertThat(row.getDetails()).isEqualTo(event.getDetails());
                                        });
                            }
                        }
                        long tenantScopeId = tenant;
                        try (var tenantScope = MDC.putCloseable("tenantId", Long.toString(tenant));
                             var actorScope = MDC.putCloseable("userId", "test-authority")) {
                            var page = repo.page(new OperationLogRepository.WindowQuery(tenant,
                                    now.minusSeconds(1), now.plusSeconds(1), null, null, 11, Map.of("actionCode", "TEST")));
                            assertThat(page).hasSizeGreaterThanOrEqualTo(2).isSortedAccordingTo(
                                    java.util.Comparator.comparing(OperationLogRepository.PageRow::occurredAt)
                                            .thenComparing(OperationLogRepository.PageRow::id).reversed());
                            assertThat(page).allSatisfy(row -> assertThat(row.tenantId()).isEqualTo(tenantScopeId));
                            var top = page.getFirst();
                            var next = repo.page(new OperationLogRepository.WindowQuery(tenant,
                                    now.minusSeconds(1), now.plusSeconds(1), top.occurredAt(), top.id(), 11, Map.of()));
                            assertThat(next).containsExactlyElementsOf(page.subList(1, page.size()));
                            var missingPartition = event(tenant, base++, YearMonth.now(ZoneOffset.UTC).plusMonths(4)
                                    .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                            assertThatThrownBy(() -> repo.accept(missingPartition))
                                    .hasStackTraceContaining("no partition");
                            String receipt = node(route, profile, tenant, missingPartition.getEventId()).table()
                                    .replace("operation_log_", "operation_log_receipt_");
                            assertThat(physicalCount(url(slot, "PRIMARY"), receipt, missingPartition.getEventId())).isZero();
                        }
                    }
                    var duplicate = event(7, base, now);
                    try (var executor = Executors.newFixedThreadPool(2)) {
                        var first = executor.submit(() -> acceptForTenant(repo, duplicate));
                        var second = executor.submit(() -> acceptForTenant(repo, duplicate));
                        first.get(20, TimeUnit.SECONDS);
                        second.get(20, TimeUnit.SECONDS);
                    }
                });
    }

    private static void acceptForTenant(OperationLogRepository repo, OperationLogEvent event) {
        try (var tenant = MDC.putCloseable("tenantId", event.getTenantId().toString());
             var user = MDC.putCloseable("userId", "test-authority")) {
            repo.accept(event);
        }
    }

    private static top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO node(
            EgonColaTwoLevelRouteStrategy route, EgonColaRoutingProfileBO profile, long tenant, long id) {
        return route.route(profile, new EgonColaRouteQuery(profile.logicalTable(), tenant,
                List.of(id), EgonColaRouteQuery.OperationEnum.COMMAND, false)).targets().getFirst();
    }

    private static OperationLogEvent event(long tenant, long id, Instant at) {
        return OperationLogEvent.builder().eventId(id).operationId(Long.toString(id))
                .tenantId(tenant).schemaVersion(1).sourceService("audit-test")
                .moduleCode("CONFIG").actionCode("TEST").resourceType("CONFIG")
                .actorType("SYSTEM").actorId("test-authority").triggerType("JOB")
                .phase(OperationLogPhaseEnum.COMMAND).outcome(OperationLogOutcomeEnum.SUCCEEDED)
                .handlerMethod("example.Audit#run()").occurredAt(at).durationMs(0L)
                .details(JsonNodeFactory.instance.objectNode().put("count", 1)).build();
    }

    private static void awaitReplay(String url, String table, long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (physicalCount(url, table, id) == 1L) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Replica did not replay the expected operation log");
    }

    private static long physicalCount(String url, String table, long id) throws SQLException {
        assertThat(table).matches("egon_cola_operation_log_(receipt_)?t[01]_b[01]");
        try (var connection = connection(url);
             var statement = connection.prepareStatement("select count(*) from " + table + " where id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static long replicationIdentity(String url) throws SQLException {
        try (var connection = connection(url);
             var statement = connection.prepareStatement("select system_identifier from pg_control_system()");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static void verifyRole(String url, boolean replica) throws Exception {
        try (var connection = connection(url);
             var statement = connection.prepareStatement("select pg_is_in_recovery()");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isEqualTo(replica);
        }
    }

    private static java.sql.Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, required("EGON_MP_PG_USER"), required("EGON_MP_PG_PASSWORD"));
    }

    private static String url(int slot, String role) {
        return required("EGON_MP_PG_SHARD_" + slot + "_" + role + "_URL");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing dedicated test setting: " + name);
        }
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    static class Host {

        @Bean("egonColaPhysicalDataSourceFactory")
        EgonColaPhysicalDataSourceFactory pools() {
            return new EgonColaPhysicalDataSourceFactory();
        }

        @Bean("egonColaShardingYamlLoader")
        EgonColaShardingYamlLoader loader(ValidationUtils validation, EgonColaTwoLevelRouteStrategy routes,
                ResourceLoader resources, Environment env) {
            return new EgonColaShardingYamlLoader(new EgonColaShardingStrategyFactory(validation,
                    Map.of("COMPLEX_TENANT_THEN_BUSINESS", new EgonColaComplexTenantThenBusinessShardingStrategy(routes))),
                    resources, env);
        }

        @Bean("egonColaShardingTopologyValidator")
        EgonColaShardingTopologyValidator topology(ValidationUtils validation) {
            return new EgonColaShardingTopologyValidator(validation);
        }

        @Bean("egonColaRoutingProfiles")
        Map<String, EgonColaRoutingProfileBO> sourceProfiles() {
            return Map.of();
        }

        @Bean("egonColaWriteTargetResolver")
        EgonColaWriteTargetResolver sourceResolver() {
            return query -> { throw new IllegalStateException("The source must not receive audit SQL"); };
        }

        @Bean("dataSource")
        DataSource source() {
            return new DriverManagerDataSource("jdbc:h2:mem:operation_log_source", "sa", "");
        }

        @Bean("transactionManager")
        DataSourceTransactionManager sourceManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean("jacksonObjectMapper")
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }
}
