package top.egon.cola.component.common.mybatis.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.annotation.TableName;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaTechnicalTenantScope;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaLocalWriteGuardInnerInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaTenantIdGuardInnerInterceptor;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteResult;
import top.egon.cola.component.common.mybatis.routing.EgonColaWriteTargetResolver;
import top.egon.cola.component.common.mybatis.support.TestTenantIdProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EgonColaLocalWriteGuardTest {

    @BeforeAll
    static void publishTenantContext() {
        new TestTenantIdProvider().set(41L);
    }

    @AfterAll
    static void clearTenantContext() {
        new TestTenantIdProvider().clear();
    }

    @Test
    void anotherWriteGroupMarksRollbackOnlyBeforeAnySecondSql() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        MybatisConfiguration configuration = configuration(source);
        ConnectionHolder holder = begin(source, connection);
        try {
            var guard = guard(query -> result(query.logicalTable().equals("a") ? "primary_a" : "primary_b", query.logicalTable()));
            guard.beforePrepare(handler(configuration, "a", SqlCommandType.UPDATE), connection, null);
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "b", SqlCommandType.UPDATE), connection, null))
                    .hasMessageContaining("LOCAL_WRITE_TARGET_MISMATCH");
            assertThat(holder.isRollbackOnly()).isTrue();
            verifyNoInteractions(connection);
        } finally {
            end(source);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void twoTablesInOneGroupAreAllowedButAnotherFactoryIsNot() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        MybatisConfiguration configuration = configuration(source);
        ConnectionHolder holder = begin(source, connection);
        try {
            var guard = guard(query -> result("primary_a", query.logicalTable()));
            guard.beforePrepare(handler(configuration, "a", SqlCommandType.UPDATE), connection, null);
            guard.beforePrepare(handler(configuration, "b", SqlCommandType.UPDATE), connection, null);
            assertThat(holder.isRollbackOnly()).isFalse();
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(source), "b", SqlCommandType.UPDATE), connection, null))
                    .hasMessageContaining("LOCAL_WRITE_TARGET_MISMATCH");
            assertThat(holder.isRollbackOnly()).isTrue();
        } finally {
            end(source);
        }
    }

    @Test
    void multipleTargetsAreRejectedOutsideATransaction() {
        DataSource source = mock(DataSource.class);
        var guard = guard(query -> new EgonColaRouteResult(List.of(
                new EgonColaPhysicalTargetBO("primary_a", "public", "a_0"),
                new EgonColaPhysicalTargetBO("primary_a", "public", "a_1")), "a".repeat(64)));
        assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(source), "a", SqlCommandType.UPDATE), mock(Connection.class), null))
                .hasMessageContaining("LOCAL_TRANSACTION_REQUIRED");
    }

    @Test
    void dmlSubqueriesMustResolveToTheSamePrimaryAndConflictsRollBack() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        var configuration = configuration(source);
        ConnectionHolder holder = begin(source, connection);
        String sql = "UPDATE a SET version=version+1, update_user_id=#{user}, update_time=#{now} "
                + "WHERE id=#{id} AND tenant_id=#{tenantId} AND version=#{version} AND deleted_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM b WHERE b.tenant_id=#{tenantId} AND b.deleted_at IS NULL)";
        try {
            var guard = guard(query -> result(query.logicalTable().equals("a") ? "primary_a" : "primary_b", query.logicalTable()));
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "test.Mapper.check", SqlCommandType.UPDATE,
                    sql, parameters()), connection, null)).hasMessageContaining("LOCAL_WRITE_TARGET_MISMATCH");
            assertThat(holder.isRollbackOnly()).isTrue();
            verifyNoInteractions(connection);
        } finally {
            end(source);
        }
    }

    @Test
    void dmlSubqueriesInTheSamePrimaryDoNotCountAsAdditionalWriteTargets() {
        DataSource source = mock(DataSource.class);
        var tables = new java.util.ArrayList<String>();
        var guard = guard(query -> {
            tables.add(query.logicalTable());
            assertThat(query.operation()).isEqualTo(top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery.OperationEnum.COMMAND);
            return result("primary_a", query.logicalTable());
        });
        String sql = "UPDATE a SET version=version+1, update_user_id=#{user}, update_time=#{now} "
                + "WHERE id=#{id} AND tenant_id=#{tenantId} AND version=#{version} AND deleted_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM b WHERE b.tenant_id=#{tenantId} AND b.deleted_at IS NULL)";
        assertThatCode(() -> guard.beforePrepare(handler(configuration(source), "test.Mapper.check", SqlCommandType.UPDATE,
                sql, parameters()), mock(Connection.class), null)).doesNotThrowAnyException();
        assertThat(tables).containsExactly("a", "b");
    }

    @Test
    void dmlCannotHideAnUnroutableSubqueryInAnIgnoredTable() {
        DataSource source = mock(DataSource.class);
        var properties = new EgonColaMybatisPlusProperties();
        properties.getTenantId().getIgnoredTables().add("b");
        var sqlGuard = new EgonColaTenantIdGuardInnerInterceptor(() -> "tester", properties);
        var guard = new EgonColaLocalWriteGuardInnerInterceptor(query -> result("primary_a", query.logicalTable()),
                sqlGuard, mock(top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy.class), Map.of());
        String sql = "UPDATE a SET version=version+1, update_user_id=#{user}, update_time=#{now} "
                + "WHERE id=#{id} AND tenant_id=#{tenantId} AND version=#{version} AND deleted_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM b WHERE b.deleted_at IS NULL)";
        assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(source), "test.Mapper.check", SqlCommandType.UPDATE,
                sql, parameters()), mock(Connection.class), null)).hasMessageContaining("DML_IGNORED_SCOPE_UNRESOLVED");
    }

    @Test
    void updateAssignmentsCannotReadAnotherPrimaryThroughAScalarSubquery() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        ConnectionHolder holder = begin(source, connection);
        String sql = "UPDATE a SET title=(SELECT max(b.title) FROM b WHERE b.tenant_id=#{tenantId} AND b.deleted_at IS NULL), "
                + "version=version+1, update_user_id=#{user}, update_time=#{now} "
                + "WHERE id=#{id} AND tenant_id=#{tenantId} AND version=#{version} AND deleted_at IS NULL";
        try {
            var guard = guard(query -> result(query.logicalTable().equals("a") ? "primary_a" : "primary_b", query.logicalTable()));
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(source), "test.Mapper.check", SqlCommandType.UPDATE,
                    sql, parameters()), connection, null)).hasMessageContaining("LOCAL_WRITE_TARGET_MISMATCH");
            assertThat(holder.isRollbackOnly()).isTrue();
        } finally {
            end(source);
        }
    }

    @Test
    void dmlSubqueryCannotFanOutToMultipleTablesInTheSamePrimary() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        ConnectionHolder holder = begin(source, connection);
        String sql = "UPDATE a SET version=version+1, update_user_id=#{user}, update_time=#{now} "
                + "WHERE id=#{id} AND tenant_id=#{tenantId} AND version=#{version} AND deleted_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM b WHERE b.tenant_id=#{tenantId} AND b.deleted_at IS NULL)";
        try {
            var guard = guard(query -> query.logicalTable().equals("a") ? result("primary_a", "a")
                    : new EgonColaRouteResult(List.of(new EgonColaPhysicalTargetBO("primary_a", "public", "b_0"),
                            new EgonColaPhysicalTargetBO("primary_a", "public", "b_1")), "a".repeat(64)));
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(source), "test.Mapper.check", SqlCommandType.UPDATE,
                    sql, parameters()), connection, null)).hasMessageContaining("DML_SUBQUERY_FANOUT_FORBIDDEN");
            assertThat(holder.isRollbackOnly()).isTrue();
        } finally {
            end(source);
        }
    }

    @Test
    void aSuspendedTransactionDoesNotLeakItsWriteTargetIntoRequiresNew() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        MybatisConfiguration configuration = configuration(source);
        begin(source, connection);
        try {
            var guard = guard(query -> result(query.logicalTable().equals("a") ? "primary_a" : "primary_b", query.logicalTable()));
            guard.beforePrepare(handler(configuration, "a", SqlCommandType.UPDATE), connection, null);
            List<TransactionSynchronization> outer = TransactionSynchronizationManager.getSynchronizations();
            outer.forEach(TransactionSynchronization::suspend);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            guard.beforePrepare(handler(configuration, "b", SqlCommandType.UPDATE), connection, null);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            outer.forEach(sync -> { sync.resume(); TransactionSynchronizationManager.registerSynchronization(sync); });
            guard.beforePrepare(handler(configuration, "a", SqlCommandType.UPDATE), connection, null);
        } finally {
            end(source);
        }
    }

    @Test
    void configuredSecondaryKeysAreRequiredForWritesAndOnlyXmlQueriesMayFanOut() {
        try (var validators = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var strategy = new top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy(
                    new top.egon.cola.component.common.core.validation.ValidationUtils(validators.getValidator()));
            var profile = new top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO("records",
                    top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.TableKindEnum.TENANT_ID_TWO_LEVEL,
                    "mix64-v1", 1, 2, Map.of(0, "primary_a"), "order_id", "order", 0x9e3779b97f4a7c15L,
                    Map.of(new top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO(0, 0),
                            List.of(new EgonColaPhysicalTargetBO("primary_a", "public", "records_t0_b0")),
                            new top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO(0, 1),
                            List.of(new EgonColaPhysicalTargetBO("primary_a", "public", "records_t0_b1"))), 2, null);
            var properties = new EgonColaMybatisPlusProperties();
            var sql = new EgonColaTenantIdGuardInnerInterceptor(() -> "tester", properties);
            var guard = new EgonColaLocalWriteGuardInnerInterceptor(query -> { throw new AssertionError("Must use the shared two-level strategy"); },
                    sql, strategy, Map.of("records", profile));
            var configuration = configuration(mock(DataSource.class));
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "records", SqlCommandType.UPDATE), mock(Connection.class), null))
                    .hasMessageContaining("SHARDING_KEY");
            String read = "SELECT id FROM records WHERE tenant_id=#{tenantId} AND deleted_at IS NULL";
            assertThatCode(() -> guard.beforePrepare(handler(configuration, "test.Mapper.query", SqlCommandType.SELECT, read, parameters()),
                    mock(Connection.class), null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "test.Mapper.selectActiveById", SqlCommandType.SELECT, read, parameters()),
                    mock(Connection.class), null)).hasMessageContaining("SHARDING_KEY");
        }
    }

    @Test
    void fixedTechnicalTenantReadsUseTheRegisteredSingleTargetAtBeforePrepare() {
        try (var validators = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var source = mock(DataSource.class);
            var configuration = configuration(source);
            configuration.addMapper(TechnicalControlMapper.class);
            var profile = new EgonColaRoutingProfileBO("technical_control_t0", EgonColaRoutingProfileBO.TableKindEnum.SINGLE,
                    "single-v1", 1, 1, Map.of(), null, null, 0x9e3779b97f4a7c15L,
                    Map.of(new EgonColaRoutingProfileBO.PartitionKeyBO(0, 0),
                            List.of(new EgonColaPhysicalTargetBO("primary_a", "public", "technical_control_t0"))), 1, null);
            var properties = new EgonColaMybatisPlusProperties();
            var sqlGuard = new EgonColaTenantIdGuardInnerInterceptor(() -> "tester", properties);
            var strategy = new top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy(
                    new top.egon.cola.component.common.core.validation.ValidationUtils(validators.getValidator()));
            var guard = new EgonColaLocalWriteGuardInnerInterceptor(query -> {
                throw new AssertionError("Fixed technical scope must use its actual SINGLE address");
            }, sqlGuard, strategy, Map.of(profile.logicalTable(), profile));
            new TestTenantIdProvider().set(0L);
            try {
                EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                    assertThatCode(() -> guard.beforePrepare(handler(configuration,
                            TechnicalControlMapper.class.getName() + ".find", SqlCommandType.SELECT,
                            "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()),
                            mock(Connection.class), null)).doesNotThrowAnyException();
                    return null;
                });
            } finally { new TestTenantIdProvider().set(41L); }
        }
    }

    @Test
    void ordinaryLegacyScalarReadsRetainTheirSignedLongDomain() {
        var guard = guard(query -> result("primary_a", query.logicalTable()));
        var configuration = configuration(mock(DataSource.class));
        try {
            for (long tenant : new long[]{Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) {
                new TestTenantIdProvider().set(tenant);
                assertThatCode(() -> guard.beforePrepare(handler(configuration, "test.Mapper.find", SqlCommandType.SELECT,
                        "SELECT id FROM legacy_records WHERE tenant_id=#{tenantId} AND deleted_at IS NULL",
                        Map.of("tenantId", tenant)), mock(Connection.class), null)).doesNotThrowAnyException();
            }
        } finally { new TestTenantIdProvider().set(41L); }
    }

    @Test
    void technicalSingleReadsRejectBareZeroUnregisteredAndDifferentMappers() {
        var configuration = configuration(mock(DataSource.class));
        configuration.addMapper(TechnicalControlMapper.class);
        var profile = technicalProfile("primary_a", "public", EgonColaRoutingProfileBO.TableKindEnum.SINGLE);
        var guard = technicalGuard(Map.of(profile.logicalTable(), profile), new EgonColaMybatisPlusProperties());
        String read = "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL";
        new TestTenantIdProvider().set(0L);
        try {
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find",
                    SqlCommandType.SELECT, read, Map.of()), mock(Connection.class), null))
                    .hasMessageContaining("TECHNICAL_TENANT_SCOPE_REQUIRED");
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "test.Mapper.find",
                    SqlCommandType.SELECT, read, Map.of()), mock(Connection.class), null))
                    .hasMessageContaining("TECHNICAL_TENANT_SCOPE_REQUIRED");
            EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                assertThatThrownBy(() -> guard.beforePrepare(handler(configuration(mock(DataSource.class)),
                        TechnicalControlMapper.class.getName() + ".find", SqlCommandType.SELECT, read, Map.of()),
                        mock(Connection.class), null)).hasMessageContaining("TECHNICAL_TENANT_SCOPE_REQUIRED");
                assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "test.OtherMapper.find",
                        SqlCommandType.SELECT, read, Map.of()), mock(Connection.class), null))
                        .hasMessageContaining("TECHNICAL_TENANT_SCOPE_REQUIRED");
                return null;
            });
        } finally { new TestTenantIdProvider().set(41L); }
    }

    @Test
    void technicalRawSqlCannotChangeTablesOrSchemasEvenThroughIgnoredScopes() {
        var configuration = configuration(mock(DataSource.class));
        configuration.addMapper(TechnicalControlMapper.class);
        var properties = new EgonColaMybatisPlusProperties();
        properties.getTenantId().getIgnoredTables().add("foreign_table");
        var profile = technicalProfile("primary_a", "public", EgonColaRoutingProfileBO.TableKindEnum.SINGLE);
        var guard = technicalGuard(Map.of(profile.logicalTable(), profile), properties);
        new TestTenantIdProvider().set(0L);
        try {
            EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                for (String table : List.of("public.foreign_table", "wrong_schema.technical_control_t0")) {
                    assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find",
                            SqlCommandType.SELECT, "SELECT id FROM " + table + " WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()),
                            mock(Connection.class), null)).hasMessageContaining("TECHNICAL_TENANT_TABLE_MISMATCH");
                }
                return null;
            });
        } finally { new TestTenantIdProvider().set(41L); }
    }

    @Test
    void technicalRoutingRequiresConfiguredSingleProfileAndExactPhysicalSchema() {
        var configuration = configuration(mock(DataSource.class));
        configuration.addMapper(TechnicalControlMapper.class);
        String read = "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL";
        new TestTenantIdProvider().set(0L);
        try {
            EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                for (Map<String, EgonColaRoutingProfileBO> profiles : List.of(Map.<String, EgonColaRoutingProfileBO>of(),
                        Map.of("technical_control_t0", technicalProfile("primary_a", "public", EgonColaRoutingProfileBO.TableKindEnum.BROADCAST_READ_ONLY)),
                        Map.of("technical_control_t0", technicalProfile("primary_a", "wrong_schema", EgonColaRoutingProfileBO.TableKindEnum.SINGLE)))) {
                    var guard = technicalGuard(profiles, new EgonColaMybatisPlusProperties());
                    assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find",
                            SqlCommandType.SELECT, read, Map.of()), mock(Connection.class), null))
                            .hasMessageContaining("TECHNICAL_TENANT_SINGLE_REQUIRED");
                }
                return null;
            });
        } finally { new TestTenantIdProvider().set(41L); }
    }

    @Test
    void technicalReadsPinTheLocalPrimaryBeforeSubsequentPositiveTenantWrites() {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var configuration = configuration(source);
        configuration.addMapper(TechnicalControlMapper.class);
        var profile = technicalProfile("primary_a", "public", EgonColaRoutingProfileBO.TableKindEnum.SINGLE);
        var guard = technicalGuard(Map.of(profile.logicalTable(), profile), new EgonColaMybatisPlusProperties());
        ConnectionHolder holder = begin(source, connection);
        try {
            new TestTenantIdProvider().set(0L);
            EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find", SqlCommandType.SELECT,
                        "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()), connection, null);
                return null;
            });
            new TestTenantIdProvider().set(41L);
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "b", SqlCommandType.UPDATE), connection, null))
                    .hasMessageContaining("LOCAL_WRITE_TARGET_MISMATCH");
            assertThat(holder.isRollbackOnly()).isTrue();
            verifyNoInteractions(connection);
        } finally { new TestTenantIdProvider().set(41L); end(source); }
    }

    @Test
    void ignoredTechnicalPlumbingRetainsOnlyItsExactQualifiedScope() {
        var configuration = configuration(mock(DataSource.class));
        configuration.addMapper(TechnicalControlMapper.class);
        var properties = new EgonColaMybatisPlusProperties();
        properties.getTenantId().getIgnoredTables().add("technical_control_t0");
        var guard = technicalGuard(Map.of(), properties);
        new TestTenantIdProvider().set(0L);
        try {
            EgonColaTechnicalTenantScope.execute(TechnicalControlMapper.class, () -> {
                assertThatCode(() -> guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find",
                        SqlCommandType.SELECT, "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()),
                        mock(Connection.class), null)).doesNotThrowAnyException();
                assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, TechnicalControlMapper.class.getName() + ".find",
                        SqlCommandType.SELECT, "SELECT id FROM technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()),
                        mock(Connection.class), null)).hasMessageContaining("TECHNICAL_TENANT_TABLE_MISMATCH");
                return null;
            });
            assertThatThrownBy(() -> guard.beforePrepare(handler(configuration, "test.Mapper.find", SqlCommandType.SELECT,
                    "SELECT id FROM public.technical_control_t0 WHERE tenant_id=0 AND deleted_at IS NULL", Map.of()),
                    mock(Connection.class), null)).hasMessageContaining("TECHNICAL_TENANT_SCOPE_REQUIRED");
        } finally { new TestTenantIdProvider().set(41L); }
    }

    private static EgonColaRoutingProfileBO technicalProfile(String group, String schema, EgonColaRoutingProfileBO.TableKindEnum kind) {
        return new EgonColaRoutingProfileBO("technical_control_t0", kind, "single-v1", 1, 1, Map.of(), null, null,
                0x9e3779b97f4a7c15L, Map.of(new EgonColaRoutingProfileBO.PartitionKeyBO(0, 0),
                List.of(new EgonColaPhysicalTargetBO(group, schema, "technical_control_t0"))), 1, null);
    }

    private static EgonColaLocalWriteGuardInnerInterceptor technicalGuard(Map<String, EgonColaRoutingProfileBO> profiles,
            EgonColaMybatisPlusProperties properties) {
        var sqlGuard = new EgonColaTenantIdGuardInnerInterceptor(() -> "tester", properties);
        return new EgonColaLocalWriteGuardInnerInterceptor(query -> result("primary_b", query.logicalTable()), sqlGuard,
                mock(top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy.class), profiles);
    }

    @TableName(value = "technical_control_t0", schema = "public")
    public static final class TechnicalControlModel extends EgonModel<TechnicalControlModel> { }

    @EgonColaExplicitTenantScopeMapper(technicalModel = TechnicalControlModel.class,
            technicalTable = "public.technical_control_t0")
    interface TechnicalControlMapper extends EgonColaMapper<TechnicalControlModel> { }

    private static EgonColaLocalWriteGuardInnerInterceptor guard(EgonColaWriteTargetResolver resolver) {
        var properties = new EgonColaMybatisPlusProperties();
        var sqlGuard = new EgonColaTenantIdGuardInnerInterceptor(() -> "tester", properties);
        return new EgonColaLocalWriteGuardInnerInterceptor(resolver, sqlGuard, mock(top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy.class), Map.of());
    }

    private static EgonColaRouteResult result(String group, String table) {
        return new EgonColaRouteResult(List.of(new EgonColaPhysicalTargetBO(group, "public", table)), "a".repeat(64));
    }

    static MybatisConfiguration configuration(DataSource source) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        return configuration;
    }

    static StatementHandler handler(MybatisConfiguration configuration, String table, SqlCommandType command) {
        return handler(configuration, "test.Mapper.update", command,
                "UPDATE " + table + " SET title='changed', update_user_id=#{user}, update_time=#{now}, version=version+1 "
                        + "WHERE id=#{id} AND tenant_id=#{tenantId} AND deleted_at IS NULL AND version=#{version}", parameters());
    }

    static StatementHandler handler(MybatisConfiguration configuration, String id, SqlCommandType command, String sql, Object parameters) {
        MappedStatement statement = new MappedStatement.Builder(configuration, id, new RawSqlSource(configuration, sql, Map.class), command)
                .resource("test-mapper.xml").build();
        return configuration.newStatementHandler(mock(Executor.class), statement, parameters, RowBounds.DEFAULT, null,
                statement.getBoundSql(parameters));
    }

    static Map<String, Object> parameters() {
        return Map.of("id", 1L, "tenantId", 41L, "version", 0L, "user", "tester", "now", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ConnectionHolder begin(DataSource source, Connection connection) {
        ConnectionHolder holder = new ConnectionHolder(connection);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        return holder;
    }

    private static void end(DataSource source) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.unbindResource(source);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
