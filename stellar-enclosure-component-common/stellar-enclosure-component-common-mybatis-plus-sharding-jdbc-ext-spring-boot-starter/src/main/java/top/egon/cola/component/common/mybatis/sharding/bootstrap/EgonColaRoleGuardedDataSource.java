package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.truncate.Truncate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Enforces the configured physical role even when a SQL hint or a locking query bypasses normal routing. */
public final class EgonColaRoleGuardedDataSource extends AbstractDataSource implements AutoCloseable {

    private final DataSource delegate;
    private final DataSourceRoleEnum role;
    private final AtomicBoolean active;
    private final ServerIdentity expectedIdentity;
    private final Predicate<Select> permittedPrimaryRead;
    private final String url;
    private final String username;
    private final String logicalGroup;
    private final Supplier<DataSource> logicalDataSource;
    private final BooleanSupplier enforceSingleWriteGroup;

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active) {
        this(delegate, role, active, null);
    }

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active,
                                  ServerIdentity expectedIdentity) {
        this(delegate, role, active, expectedIdentity, select -> false);
    }

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active,
                                  ServerIdentity expectedIdentity, Predicate<Select> permittedPrimaryRead) {
        this(delegate, role, active, expectedIdentity, permittedPrimaryRead, null, null);
    }

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active,
                                  ServerIdentity expectedIdentity, Predicate<Select> permittedPrimaryRead,
                                  String url, String username) {
        this(delegate, role, active, expectedIdentity, permittedPrimaryRead, url, username, null, () -> null);
    }

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active,
                                  ServerIdentity expectedIdentity, Predicate<Select> permittedPrimaryRead,
                                  String url, String username, String logicalGroup, Supplier<DataSource> logicalDataSource) {
        this(delegate, role, active, expectedIdentity, permittedPrimaryRead, url, username, logicalGroup,
                logicalDataSource, () -> true);
    }

    EgonColaRoleGuardedDataSource(DataSource delegate, DataSourceRoleEnum role, AtomicBoolean active,
                                  ServerIdentity expectedIdentity, Predicate<Select> permittedPrimaryRead,
                                  String url, String username, String logicalGroup, Supplier<DataSource> logicalDataSource,
                                  BooleanSupplier enforceSingleWriteGroup) {
        this.delegate = delegate;
        this.role = role;
        this.active = active;
        this.expectedIdentity = expectedIdentity;
        this.permittedPrimaryRead = permittedPrimaryRead;
        this.url = url;
        this.username = username;
        this.logicalGroup = logicalGroup;
        this.logicalDataSource = logicalDataSource;
        this.enforceSingleWriteGroup = enforceSingleWriteGroup;
    }

    /** ShardingSphere inspects these non-secret pool properties when building its storage units. */
    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guard(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guard(delegate.getConnection(username, password));
    }

    private Connection guard(Connection connection) throws SQLException {
        try {
            if (active.get()) {
                ServerIdentity actual = readIdentity(connection, role);
                if (expectedIdentity != null && !expectedIdentity.equals(actual)) {
                    throw new SQLException("READWRITE_CLUSTER_IDENTITY_MISMATCH");
                }
                if (role == DataSourceRoleEnum.REPLICA) {
                    connection.setReadOnly(true);
                }
            }
            EgonColaTransactionWriteTarget.State target = active.get() && role == DataSourceRoleEnum.PRIMARY
                    && enforceSingleWriteGroup.getAsBoolean()
                    ? EgonColaTransactionWriteTarget.capture(logicalDataSource.get()) : null;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> connectionCall((Connection) proxy, connection, method, args, target));
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static Map<String, ServerIdentity> verifyTopology(List<PhysicalDataSourceProperties> sources,
                                                       Map<String, DataSource> physical) throws SQLException {
        Map<String, ServerIdentity> byGroup = new HashMap<>();
        Map<String, ServerIdentity> bySource = new HashMap<>();
        for (var source : sources) {
            try (Connection connection = physical.get(source.name()).getConnection()) {
                ServerIdentity identity = readIdentity(connection, source.role());
                ServerIdentity previous = byGroup.putIfAbsent(source.logicalName(), identity);
                if (previous != null && !previous.equals(identity)) {
                    throw new SQLException("READWRITE_CLUSTER_IDENTITY_MISMATCH");
                }
                bySource.put(source.name(), identity);
            }
        }
        return Map.copyOf(bySource);
    }

    private static ServerIdentity readIdentity(Connection connection, DataSourceRoleEnum role) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            throw new SQLException("POSTGRESQL_REQUIRED");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_is_in_recovery(), (pg_control_system()).system_identifier::text, current_database()")) {
            if (!result.next() || result.getBoolean(1) != (role == DataSourceRoleEnum.REPLICA)) {
                throw new SQLException("READWRITE_PHYSICAL_ROLE_MISMATCH");
            }
            String systemIdentifier = result.getString(2);
            String database = result.getString(3);
            if (systemIdentifier == null || systemIdentifier.isBlank() || database == null || database.isBlank()) {
                throw new SQLException("READWRITE_CLUSTER_IDENTITY_MISSING");
            }
            return new ServerIdentity(systemIdentifier, database);
        }
    }

    record ServerIdentity(String systemIdentifier, String database) {
    }

    private Object connectionCall(Connection proxy, Connection connection, Method method, Object[] args,
                                  EgonColaTransactionWriteTarget.State target) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        String name = method.getName();
        if (active.get() && "prepareCall".equals(name)) {
            throw new SQLException("READWRITE_CALLABLE_UNSUPPORTED");
        }
        if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
            return wrapperCall(proxy, connection, method, args);
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
                            return wrapperCall(metadataProxy, metadata, metadataMethod, metadataArgs);
                        }
                        Object result = invoke(metadata, metadataMethod, metadataArgs);
                        return result instanceof ResultSet rows ? guardResultSet(rows, null, proxy, connection, target) : result;
                    });
        }
        if (name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement")) {
            String sql = name.equals("createStatement") ? null : (String) args[0];
            if (sql != null) {
                checkSql(sql, target);
            }
            Statement statement = (Statement) invoke(connection, method, args);
            return guardStatement(statement, proxy, connection, sql, target);
        }
        return invoke(connection, method, args);
    }

    private Object guardStatement(Statement statement, Connection proxy, Connection connection, String sql,
                                  EgonColaTransactionWriteTarget.State target) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (statementProxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(statementProxy, method, args);
                    }
                    String operation = method.getName();
                    if ("getConnection".equals(operation)) {
                        return proxy;
                    }
                    if ("unwrap".equals(operation) || "isWrapperFor".equals(operation)) {
                        return wrapperCall(statementProxy, statement, method, args);
                    }
                    if (operation.startsWith("execute") || operation.equals("addBatch")) {
                        String current = args != null && args.length > 0 && args[0] instanceof String text ? text : sql;
                        if (current != null) {
                            checkSql(current, target);
                        } else if (active.get() && statement instanceof PreparedStatement) {
                            throw new SQLException("READWRITE_SQL_UNSUPPORTED");
                        }
                        if (active.get() && operation.startsWith("execute")) {
                            verifyCurrentRole(connection);
                        }
                    }
                    Object result = invoke(statement, method, args);
                    return result instanceof ResultSet rows
                            ? guardResultSet(rows, (Statement) statementProxy, proxy, connection, target) : result;
                });
    }

    private Object guardResultSet(ResultSet rows, Statement guardedStatement, Connection proxy, Connection connection,
                                  EgonColaTransactionWriteTarget.State target) {
        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (resultProxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(resultProxy, method, args);
                    }
                    if ("getStatement".equals(method.getName())) {
                        if (guardedStatement != null) {
                            return guardedStatement;
                        }
                        Statement raw = rows.getStatement();
                        return raw == null ? null : guardStatement(raw, proxy, connection, null, target);
                    }
                    if ("unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName())) {
                        return wrapperCall(resultProxy, rows, method, args);
                    }
                    return invoke(rows, method, args);
                });
    }

    private void checkSql(String sql, EgonColaTransactionWriteTarget.State target) throws SQLException {
        if (!active.get()) {
            return;
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1) {
                throw new SQLException("READWRITE_SQL_UNSUPPORTED");
            }
            var statement = statements.get(0);
            boolean allowed = role == DataSourceRoleEnum.REPLICA
                    ? statement instanceof Select
                    : statement instanceof Insert || statement instanceof Update || statement instanceof Delete
                            || statement instanceof Merge || statement instanceof Truncate
                            || statement instanceof Select select && permittedPrimaryRead.test(select);
            if (!allowed) {
                throw new SQLException("READWRITE_SQL_ROLE_MISMATCH");
            }
            if (role == DataSourceRoleEnum.PRIMARY && !(statement instanceof Select) && logicalGroup != null
                    && enforceSingleWriteGroup.getAsBoolean()) {
                EgonColaTransactionWriteTarget.State effective = target == null
                        ? EgonColaTransactionWriteTarget.capture(logicalDataSource.get()) : target;
                if (effective != null) {
                    try {
                        effective.pin(logicalGroup, null);
                    } catch (IllegalStateException failure) {
                        throw new SQLException(failure.getMessage(), failure);
                    }
                }
            }
        } catch (JSQLParserException failure) {
            throw new SQLException("READWRITE_SQL_UNSUPPORTED", failure);
        }
    }

    private void verifyCurrentRole(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_is_in_recovery()")) {
            if (!result.next() || result.getBoolean(1) != (role == DataSourceRoleEnum.REPLICA)) {
                throw new SQLException("READWRITE_PHYSICAL_ROLE_MISMATCH");
            }
        }
    }

    private Object wrapperCall(Object proxy, Object target, Method method, Object[] args) throws Throwable {
        if (!active.get()) {
            return invoke(target, method, args);
        }
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
