package top.egon.cola.component.common.mybatis.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteResult;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaWriteTargetResolver;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;

import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Final SQL and physical-write boundary, scoped to the Spring transaction rather than a thread lifetime. */
@Slf4j
@RequiredArgsConstructor
public final class EgonColaLocalWriteGuardInnerInterceptor implements InnerInterceptor {

    @Qualifier("egonColaWriteTargetResolver")
    private final EgonColaWriteTargetResolver resolver;
    @Qualifier("egonColaTenantIdGuardInnerInterceptor")
    private final EgonColaTenantIdGuardInnerInterceptor sqlGuard;
    @Qualifier("egonColaTwoLevelRouteStrategy")
    private final EgonColaTwoLevelRouteStrategy strategy;
    @Qualifier("egonColaRoutingProfiles")
    private final Map<String, EgonColaRoutingProfileBO> profiles;

    @Override
    public void beforePrepare(StatementHandler handler, Connection connection, Integer timeout) {
        var wrapped = PluginUtils.mpStatementHandler(handler);
        var statement = wrapped.mappedStatement();
        Configuration factory = wrapped.configuration();
        List<EgonColaTenantIdGuardInnerInterceptor.ScopedRouteQuery> queries;
        try {
            queries = sqlGuard.validateFinal(statement, handler.getBoundSql(), profiles);
        } catch (RuntimeException failure) {
            markRollbackOnly(factory);
            throw failure;
        }
        boolean write = statement.getSqlCommandType() != SqlCommandType.SELECT;
        Set<String> groups = new LinkedHashSet<>();
        int targetCount = 0;
        try {
            for (EgonColaTenantIdGuardInnerInterceptor.ScopedRouteQuery scoped : queries) {
                EgonColaRouteQuery query = scoped.query();
                EgonColaRoutingProfileBO profile = profiles.get(query.logicalTable());
                if (write && profile != null && profile.kind() == EgonColaRoutingProfileBO.TableKindEnum.BROADCAST_READ_ONLY) {
                    throw new IllegalStateException("BROADCAST_READ_ONLY");
                }
                EgonColaRouteResult result = profile != null && profile.kind() != EgonColaRoutingProfileBO.TableKindEnum.TENANT_LEGACY
                        ? strategy.route(profile, query) : resolver.resolve(query);
                if (result == null || result.targets().isEmpty()) { throw new IllegalStateException("WRITE_TARGET_UNRESOLVED"); }
                if (scoped.schema() != null && result.targets().stream().anyMatch(target -> !scoped.schema().equals(target.schema()))) {
                    throw new IllegalStateException("SQL_SCHEMA_ROUTE_MISMATCH");
                }
                if (write) {
                    result.targets().forEach(target -> groups.add(target.group()));
                    targetCount += result.targets().size();
                }
            }
            if (sqlGuard.requiresLifecycleTransaction(statement)
                    && (!TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.isSynchronizationActive()
                        || !(TransactionSynchronizationManager.getResource(factory.getEnvironment().getDataSource()) instanceof ConnectionHolder))) {
                throw new IllegalStateException("PURGE_TRANSACTION_REQUIRED");
            }
            if (!write || queries.isEmpty()) { return; }
            if (groups.size() != 1) { throw new IllegalStateException("LOCAL_WRITE_TARGET_MISMATCH"); }
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            if (!active) {
                if (targetCount != 1) { throw new IllegalStateException("LOCAL_TRANSACTION_REQUIRED"); }
                return;
            }
            String group = groups.iterator().next();
            var target = EgonColaTransactionWriteTarget.capture(factory.getEnvironment().getDataSource());
            if (target == null) {
                throw new IllegalStateException("LOCAL_TRANSACTION_RESOURCE_MISSING");
            }
            target.pin(group, factory);
        } catch (RuntimeException failure) {
            markRollbackOnly(factory);
            throw failure;
        }
    }

    private static void markRollbackOnly(Configuration factory) {
        EgonColaTransactionWriteTarget.markRollbackOnly(
                factory.getEnvironment() == null ? null : factory.getEnvironment().getDataSource());
    }
}
