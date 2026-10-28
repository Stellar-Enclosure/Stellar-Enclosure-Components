package top.egon.cola.component.common.mybatis.interceptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.LifecyclePolicyBO;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.LifecycleScopeEnum;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteResult;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.routing.EgonColaWriteTargetResolver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EgonColaMonotoneDeleteRoutingTest {
    private static final String ID = "test.LifecycleMapper.deleteVersionedById";
    private static final String SQL = "UPDATE item SET deleted_at=GREATEST((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), "
            + "COALESCE((SELECT MAX(history.deleted_at) + INTERVAL '0.000001' SECOND FROM item history "
            + "WHERE history.tenant_id = #{tenantId} AND history.sn = #{sn}), (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'))), "
            + "version=version+1, update_user_id=#{user}, update_time=#{now} "
            + "WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND sn=#{sn} AND deleted_at IS NULL";

    @BeforeEach
    void setTenant() {
        EgonColaTenantIdProvider.initialize("tenantId");
        MDC.put("tenantId", "41");
    }

    @AfterEach
    void clearTenant() {
        MDC.clear();
    }

    @Test
    void routesTheHistoryReadAndWriteToTheSamePrimary() {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        List<EgonColaRouteQuery> queries = new ArrayList<>();
        var guard = guard(query -> {
            queries.add(query);
            return result("primary_a", "item_0");
        });
        var statementHandler = handler(source);
        assertThatCode(() -> tenantGuard().beforePrepare(statementHandler, connection, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.beforePrepare(statementHandler, connection, null)).doesNotThrowAnyException();
        assertThat(queries).hasSize(2).allSatisfy(query -> {
            assertThat(query.logicalTable()).isEqualTo("item");
            assertThat(query.tenantId()).isEqualTo(41L);
            assertThat(query.operation()).isEqualTo(EgonColaRouteQuery.OperationEnum.COMMAND);
        });
        verifyNoInteractions(connection);
    }

    @Test
    void rejectsAHistoryReadOnAnotherPrimaryAndMarksTheTransactionForRollback() {
        AtomicInteger calls = new AtomicInteger();
        assertRejected(query -> result(calls.getAndIncrement() == 0 ? "primary_a" : "primary_b", "item_0"),
                "LOCAL_WRITE_TARGET_MISMATCH");
    }

    @Test
    void rejectsAHistoryReadThatFansOutAcrossTables() {
        AtomicInteger calls = new AtomicInteger();
        assertRejected(query -> calls.getAndIncrement() == 0 ? result("primary_a", "item_0")
                : new EgonColaRouteResult(List.of(new EgonColaPhysicalTargetBO("primary_a", "public", "item_0"),
                        new EgonColaPhysicalTargetBO("primary_a", "public", "item_1")), "a".repeat(64)),
                "DML_SUBQUERY_FANOUT_FORBIDDEN");
    }

    private static void assertRejected(EgonColaWriteTargetResolver resolver, String code) {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        ConnectionHolder holder = new ConnectionHolder(connection);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        try {
            assertThatThrownBy(() -> guard(resolver).beforePrepare(handler(source), connection, null)).hasMessage(code);
            assertThat(holder.isRollbackOnly()).isTrue();
            verifyNoInteractions(connection);
        } finally {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static EgonColaLocalWriteGuardInnerInterceptor guard(EgonColaWriteTargetResolver resolver) {
        return new EgonColaLocalWriteGuardInnerInterceptor(resolver, tenantGuard(), mock(EgonColaTwoLevelRouteStrategy.class), Map.of());
    }

    private static EgonColaTenantIdGuardInnerInterceptor tenantGuard() {
        var properties = new EgonColaMybatisPlusProperties();
        properties.getLifecycle().getStatementPolicies().put(ID,
                LifecyclePolicyBO.builder().scope(LifecycleScopeEnum.MONOTONE_DELETE).table("item").build());
        var sqlGuard = new EgonColaTenantIdGuardInnerInterceptor(() -> "actor", properties);
        sqlGuard.snapshotLifecycleStatements();
        return sqlGuard;
    }

    private static StatementHandler handler(DataSource source) {
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        Map<String, Object> parameters = Map.of("tenantId", 41L, "sn", "sku", "id", 1L, "version", 3L,
                "user", "actor", "now", Instant.parse("2026-01-01T00:00:00Z"));
        var statement = new MappedStatement.Builder(configuration, ID, new RawSqlSource(configuration, SQL, Map.class), SqlCommandType.UPDATE)
                .resource("test-mapper.xml").build();
        return configuration.newStatementHandler(mock(Executor.class), statement, parameters, RowBounds.DEFAULT, null,
                statement.getBoundSql(parameters));
    }

    private static EgonColaRouteResult result(String group, String table) {
        return new EgonColaRouteResult(List.of(new EgonColaPhysicalTargetBO(group, "public", table)), "a".repeat(64));
    }
}
