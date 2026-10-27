package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import org.apache.shardingsphere.infra.hint.HintManager;
import org.apache.shardingsphere.infra.hint.SQLHintUtils;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Rejects hints that can bypass validated table and read/write routing. */
public final class EgonColaRouteHintGuardedDataSource extends AbstractDataSource implements AutoCloseable {

    private final DataSource delegate;
    private final LogicalSqlPolicy sqlPolicy;
    private final java.util.function.BooleanSupplier enforceSingleWriteGroup;

    public EgonColaRouteHintGuardedDataSource(DataSource delegate) {
        this(delegate, sql -> { });
    }

    public EgonColaRouteHintGuardedDataSource(DataSource delegate, LogicalSqlPolicy sqlPolicy) {
        this(delegate, sqlPolicy, () -> false);
    }

    public EgonColaRouteHintGuardedDataSource(DataSource delegate, LogicalSqlPolicy sqlPolicy,
                                             java.util.function.BooleanSupplier enforceSingleWriteGroup) {
        this.delegate = delegate;
        this.sqlPolicy = sqlPolicy;
        this.enforceSingleWriteGroup = enforceSingleWriteGroup;
    }

    @Override
    public Connection getConnection() throws SQLException {
        rejectUnmanagedTransactionConnection();
        return guard(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        rejectUnmanagedTransactionConnection();
        return guard(delegate.getConnection(username, password));
    }

    private void rejectUnmanagedTransactionConnection() throws SQLException {
        if (enforceSingleWriteGroup.getAsBoolean()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(this)
                        instanceof org.springframework.jdbc.datasource.ConnectionHolder holder
                && holder.getConnectionHandle() != null) {
            top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget.markRollbackOnly(this);
            throw new SQLException("READWRITE_UNMANAGED_TRANSACTION_CONNECTION");
        }
    }

    private Connection guard(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> connectionCall((Connection) proxy, connection, method, args));
    }

    private Object connectionCall(Connection proxy, Connection connection, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        String name = method.getName();
        if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
            return wrapperCall(proxy, method, args);
        }
        if ("getMetaData".equals(name)) {
            DatabaseMetaData metadata = (DatabaseMetaData) invoke(connection, method, args);
            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                    (metadataProxy, metadataMethod, metadataArgs) -> {
                        if (metadataMethod.getDeclaringClass() == Object.class) {
                            return objectMethod(metadataProxy, metadataMethod, metadataArgs);
                        }
                        if ("getConnection".equals(metadataMethod.getName())) {
                            return proxy;
                        }
                        if ("unwrap".equals(metadataMethod.getName()) || "isWrapperFor".equals(metadataMethod.getName())) {
                            return wrapperCall(metadataProxy, metadataMethod, metadataArgs);
                        }
                        Object result = invoke(metadata, metadataMethod, metadataArgs);
                        return result instanceof ResultSet rows ? guardResultSet(rows, null, proxy) : result;
                    });
        }
        if (name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement")) {
            String sql = name.equals("createStatement") ? null : (String) args[0];
            checkHints(sql);
            return guardStatement((Statement) invoke(connection, method, args), proxy, sql);
        }
        return invoke(connection, method, args);
    }

    private Object guardStatement(Statement statement, Connection connection, String sql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }
            String name = method.getName();
            if ("getConnection".equals(name)) {
                return connection;
            }
            if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                return wrapperCall(proxy, method, args);
            }
            if (name.startsWith("execute") || "addBatch".equals(name)) {
                String current = args != null && args.length > 0 && args[0] instanceof String text ? text : sql;
                checkHints(current);
            }
            Object result = invoke(statement, method, args);
            return result instanceof ResultSet rows ? guardResultSet(rows, (Statement) proxy, connection) : result;
        });
    }

    private Object guardResultSet(ResultSet rows, Statement statement, Connection connection) {
        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    if ("getStatement".equals(method.getName())) {
                        if (statement != null) {
                            return statement;
                        }
                        Statement raw = rows.getStatement();
                        return raw == null ? null : guardStatement(raw, connection, null);
                    }
                    if ("unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName())) {
                        return wrapperCall(proxy, method, args);
                    }
                    return invoke(rows, method, args);
                });
    }

    private void checkHints(String sql) throws SQLException {
        if (HintManager.isInstantiated()) {
            throw new SQLException("READWRITE_HINT_FORBIDDEN");
        }
        if (sql == null) {
            return;
        }
        try {
            sqlPolicy.validate(sql);
        } catch (SQLException | RuntimeException failure) {
            top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget.markRollbackOnly(this);
            throw failure;
        }
        try {
            var hint = SQLHintUtils.extractHint(sql);
            if (hint.findHintDataSourceName().isPresent() || hint.isWriteRouteOnly()
                    || hint.isSkipSQLRewrite() || hint.isSkipMetadataValidate() || hint.isShadow()
                    || !hint.getShardingDatabaseValues().isEmpty() || !hint.getShardingTableValues().isEmpty()
                    || !hint.getDisableAuditNames().isEmpty()) {
                throw new SQLException("READWRITE_HINT_FORBIDDEN");
            }
        } catch (RuntimeException failure) {
            throw new SQLException("READWRITE_HINT_INVALID", failure);
        }
    }

    @FunctionalInterface
    public interface LogicalSqlPolicy {
        void validate(String sql) throws SQLException;
    }

    private static Object wrapperCall(Object proxy, Method method, Object[] args) throws SQLException {
        Class<?> requested = (Class<?>) args[0];
        if ("isWrapperFor".equals(method.getName())) {
            return requested.isInstance(proxy);
        }
        if (requested.isInstance(proxy)) {
            return proxy;
        }
        throw new SQLException("READWRITE_UNWRAP_UNSUPPORTED");
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Guarded JDBC " + proxy.getClass().getInterfaces()[0].getSimpleName();
            default -> throw new IllegalStateException("Unsupported Object method");
        };
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
