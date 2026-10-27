package top.egon.cola.component.outbox.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlResult;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO.RoleEnum;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.ddl.EgonColaManagedDdlInitializer;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator.TopologyBO;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.api.OutboxShardIdentity;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares the fixed outbox schema through the shared managed-DDL runner or read-only verification path. */
@Slf4j
@Validated
public class OutboxManagedDdlInitializer {

    private static final String OUTBOX_SCHEMA = "egon_outbox";
    private static final List<String> REQUIRED_TABLES = List.of("egon_cola_outbox_message", "ddl_history");

    @Qualifier("egonColaPostgreDdlRunner")
    private final EgonColaPostgreDdlRunner ddlRunner;

    @Qualifier("egonColaShardingTopologyValidator")
    private final EgonColaShardingTopologyValidator topologyValidator;

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    @Qualifier("egon.cola.component.mybatis-plus.sharding-top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties")
    private final EgonColaShardingProperties shardingProperties;

    @Qualifier("outboxMpStorageProperties")
    private final OutboxMpStorageProperties storageProperties;

    @Qualifier("outboxStateMachineObjectMapper")
    private final ObjectMapper objectMapper;

    private final Duration topologyReadyTimeout;

    private final ResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    private volatile ReadinessBO readiness;
    private volatile boolean multiGroupMode;

    public OutboxManagedDdlInitializer(EgonColaPostgreDdlRunner ddlRunner,
                                      EgonColaShardingTopologyValidator topologyValidator,
                                      ValidationUtils validationUtils,
                                      EgonColaShardingProperties shardingProperties,
                                      OutboxMpStorageProperties storageProperties,
                                      ObjectMapper objectMapper) {
        this(ddlRunner, topologyValidator, validationUtils, shardingProperties, storageProperties,
                objectMapper, Duration.ofSeconds(60));
    }

    public OutboxManagedDdlInitializer(EgonColaPostgreDdlRunner ddlRunner,
                                      EgonColaShardingTopologyValidator topologyValidator,
                                      ValidationUtils validationUtils,
                                      EgonColaShardingProperties shardingProperties,
                                      OutboxMpStorageProperties storageProperties,
                                      ObjectMapper objectMapper,
                                      Duration topologyReadyTimeout) {
        this.ddlRunner = ddlRunner;
        this.topologyValidator = topologyValidator;
        this.validationUtils = validationUtils;
        this.shardingProperties = shardingProperties;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.topologyReadyTimeout = topologyReadyTimeout;
    }

    public synchronized void initialize(@NotEmpty Map<@NotBlank String, @NotNull DataSource> physicalSources,
                                        @NotEmpty byte[] yaml) {
        readiness = null;
        if (physicalSources == null || physicalSources.isEmpty() || yaml == null || yaml.length == 0) {
            throw new IllegalArgumentException("OUTBOX_DDL_INPUT_REQUIRED");
        }
        validationUtils.validate(shardingProperties);
        validationUtils.validate(storageProperties);
        validateStorageTopology();

        Map<String, PhysicalDataSourceProperties> primaries = OutboxStorageTopology.primaries(shardingProperties);
        multiGroupMode = primaries.size() > 1;
        if (physicalSources.size() != shardingProperties.getDataSources().size()
                || shardingProperties.getDataSources().stream().anyMatch(source -> !physicalSources.containsKey(source.name()))) {
            throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_INVALID");
        }

        TopologyBO topology = topologyValidator.validate(shardingProperties, yaml);
        Map<Integer, String> slots = validateTransactionAndNodes(topology, primaries);
        EgonColaDdlManifestBO manifest = loadManifest();
        if (storageProperties.isAutoInitializeDdl()) {
            List<EgonColaDdlTargetBO> targets = primaries.values().stream()
                    .map(primary -> new EgonColaDdlTargetBO(primary.name(), OUTBOX_SCHEMA, RoleEnum.MASTER_DATA,
                            new SchemaScopedDataSource(physicalSources.get(primary.name()), OUTBOX_SCHEMA), manifest,
                            topology.fingerprint(), Set.copyOf(storageProperties.getPreviousRouteFingerprints())))
                    .toList();
            List<EgonColaDdlResult> results = ddlRunner.run(targets);
            validateRunnerResults(results, manifest, primaries);
            log.info("outbox managed DDL ready: schema={} scripts={} routeFingerprint={}",
                    OUTBOX_SCHEMA, results.size(), topology.fingerprint());
        } else {
            log.info("outbox managed DDL skipped; read-only schema validation required: schema={} routeFingerprint={}",
                    OUTBOX_SCHEMA, topology.fingerprint());
        }
        if (shardingProperties.getMode() == ModeEnum.SHARDING_READWRITE) {
            EgonColaManagedDdlInitializer.awaitReplicaReplay(shardingProperties.getDataSources(), primaries,
                    physicalSources, topologyReadyTimeout);
        }
        Map<String, DataSource> primarySources = new TreeMap<>();
        primaries.forEach((group, primary) -> primarySources.put(group, physicalSources.get(primary.name())));
        if (primarySources.size() > 1) {
            validateShardContents(primarySources, slots);
        }
        readiness = new ReadinessBO(Map.copyOf(primarySources), topology.fingerprint());
    }

    public boolean isReadyFor(@NotNull DataSource primaryDataSource, @NotBlank String routeFingerprint) {
        ReadinessBO current = readiness;
        return current != null && current.primaryDataSources().values().stream().anyMatch(source -> source == primaryDataSource)
                && current.routeFingerprint().equals(routeFingerprint);
    }

    public boolean isReadwriteMode() {
        return shardingProperties.getMode() == ModeEnum.SHARDING_READWRITE;
    }

    public boolean isMultiGroupMode() {
        return multiGroupMode;
    }

    /** Returns the retained physical PRIMARY only for read-only metadata checks after bootstrap readiness. */
    public DataSource physicalMetadataDataSource(@NotBlank String routeFingerprint) {
        if (routeFingerprint == null || routeFingerprint.isBlank()) {
            throw new IllegalArgumentException("OUTBOX_DDL_ROUTE_FINGERPRINT_REQUIRED");
        }
        ReadinessBO current = readiness;
        if (current == null || !current.routeFingerprint().equals(routeFingerprint)) {
            throw new IllegalStateException("OUTBOX_DDL_READINESS_MISSING");
        }
        if (current.primaryDataSources().size() != 1) {
            throw new IllegalStateException("OUTBOX_SINGLE_PRIMARY_REQUIRED");
        }
        return current.primaryDataSources().values().iterator().next();
    }

    public Map<String, DataSource> physicalMetadataDataSources(@NotBlank String routeFingerprint) {
        ReadinessBO current = readiness;
        if (current == null || routeFingerprint == null || !current.routeFingerprint().equals(routeFingerprint)) {
            throw new IllegalStateException("OUTBOX_DDL_READINESS_MISSING");
        }
        return current.primaryDataSources();
    }

    private void validateStorageTopology() {
        if ((shardingProperties.getMode() != ModeEnum.SHARDING
                && shardingProperties.getMode() != ModeEnum.SHARDING_READWRITE)
                || !"LOCAL".equals(shardingProperties.getTransactionDefaultType())) {
            throw new IllegalStateException("OUTBOX_SHARDING_LOCAL_REQUIRED");
        }
        OutboxStorageTopology.primaries(shardingProperties);
        if (!"db/egon-outbox-mp/manifest.json".equals(storageProperties.getManifestResource())) {
            throw new IllegalStateException("OUTBOX_DDL_MANIFEST_RESOURCE_FIXED");
        }
    }

    private Map<Integer, String> validateTransactionAndNodes(TopologyBO topology,
                                                              Map<String, PhysicalDataSourceProperties> primaries) {
        org.apache.shardingsphere.driver.yaml.YamlJDBCConfiguration configuration =
                org.apache.shardingsphere.infra.util.yaml.YamlEngine.unmarshal(
                        new String(topology.yaml(), java.nio.charset.StandardCharsets.UTF_8),
                        org.apache.shardingsphere.driver.yaml.YamlJDBCConfiguration.class);
        if (configuration.getTransaction() == null
                || !"LOCAL".equals(configuration.getTransaction().getDefaultType())) {
            throw new IllegalStateException("OUTBOX_LOCAL_TRANSACTION_REQUIRED");
        }
        EgonColaRoutingProfileBO message = topology.profiles().get(REQUIRED_TABLES.getFirst());
        if (message == null) {
            throw new IllegalStateException("OUTBOX_SINGLE_NODE_REQUIRED: " + REQUIRED_TABLES.getFirst());
        }
        Map<Integer, String> slots;
        if (message.kind() == EgonColaRoutingProfileBO.TableKindEnum.SINGLE && primaries.size() == 1) {
            PhysicalDataSourceProperties only = primaries.values().iterator().next();
            String group = shardingProperties.getMode() == ModeEnum.SHARDING ? only.name() : only.logicalName();
            slots = Map.of(0, group);
        } else if (message.kind() == EgonColaRoutingProfileBO.TableKindEnum.TECHNICAL_GROUP
                && Set.copyOf(message.tenantSlotMap().values()).equals(primaries.keySet())) {
            slots = message.tenantSlotMap();
        } else {
            throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_REQUIRED");
        }
        List<EgonColaPhysicalTargetBO> messageNodes = message.actualNodes().get(new PartitionKeyBO(0, 0));
        if (messageNodes == null || messageNodes.size() != slots.size()
                || messageNodes.stream().anyMatch(node -> !slots.containsValue(node.group())
                        || !OUTBOX_SCHEMA.equals(node.schema()) || !REQUIRED_TABLES.getFirst().equals(node.table()))) {
            throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_MISMATCH");
        }
        for (String table : REQUIRED_TABLES.subList(1, REQUIRED_TABLES.size())) {
            EgonColaRoutingProfileBO profile = topology.profiles().get(table);
            if (profile == null || profile.kind() != EgonColaRoutingProfileBO.TableKindEnum.SINGLE
                    || profile.actualNodes().size() != 1) {
                throw new IllegalStateException("OUTBOX_SINGLE_NODE_REQUIRED: " + table);
            }
            List<EgonColaPhysicalTargetBO> nodes = profile.actualNodes().get(new PartitionKeyBO(0, 0));
            if (nodes == null || nodes.size() != 1) {
                throw new IllegalStateException("OUTBOX_SINGLE_NODE_REQUIRED: " + table);
            }
            EgonColaPhysicalTargetBO node = nodes.getFirst();
            if (!slots.get(0).equals(node.group()) || !OUTBOX_SCHEMA.equals(node.schema()) || !table.equals(node.table())) {
                throw new IllegalStateException("OUTBOX_SINGLE_NODE_MISMATCH: " + table);
            }
        }
        return slots;
    }

    private EgonColaDdlManifestBO loadManifest() {
        String resourcePath = "classpath*:" + storageProperties.getManifestResource();
        try {
            var matches = resources.getResources(resourcePath);
            if (matches.length != 1) {
                throw new IllegalStateException("OUTBOX_DDL_MANIFEST_RESOURCE_AMBIGUOUS");
            }
            try (var input = matches[0].getInputStream()) {
                EgonColaDdlManifestBO manifest = objectMapper.readValue(input, EgonColaDdlManifestBO.class);
                validationUtils.validate(manifest);
                if (!"component-outbox".equals(manifest.family())) {
                    throw new IllegalStateException("OUTBOX_DDL_MANIFEST_FAMILY_INVALID");
                }
                return manifest;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("OUTBOX_DDL_MANIFEST_UNREADABLE", failure);
        }
    }

    private void validateRunnerResults(List<EgonColaDdlResult> results, EgonColaDdlManifestBO manifest,
                                       Map<String, PhysicalDataSourceProperties> primaries) {
        List<String> aliases = primaries.values().stream().map(PhysicalDataSourceProperties::name).sorted().toList();
        if (results == null || results.size() != manifest.scripts().size() * aliases.size()) {
            throw new IllegalStateException("OUTBOX_DDL_MANIFEST_INCOMPLETE");
        }
        for (int index = 0; index < results.size(); index++) {
            EgonColaDdlResult result = validationUtils.validate(results.get(index));
            EgonColaDdlManifestBO.ScriptBO script = manifest.scripts().get(index % manifest.scripts().size());
            if (!aliases.get(index / manifest.scripts().size()).equals(result.alias()) || !OUTBOX_SCHEMA.equals(result.schema())
                    || !script.version().equals(result.version()) || !script.sha256().equals(result.checksum())) {
                throw new IllegalStateException("OUTBOX_DDL_RESULT_MISMATCH");
            }
        }
    }

    private void validateShardContents(Map<String, DataSource> primarySources, Map<Integer, String> slots) {
        int queryTimeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, topologyReadyTimeout.toSeconds()));
        for (var shard : primarySources.entrySet()) {
            int expected = slots.entrySet().stream().filter(entry -> entry.getValue().equals(shard.getKey()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            try (Connection connection = shard.getValue().getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT shard_slot, message_id, idempotency_key "
                        + "FROM egon_outbox.egon_cola_outbox_message")) {
                    statement.setQueryTimeout(queryTimeout);
                    statement.setFetchSize(512);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            int slot = rows.getInt(1);
                            boolean slotNull = rows.wasNull();
                            String messageId = rows.getString(2);
                            String key = rows.getString(3);
                            if (slotNull || slot != expected
                                    || OutboxShardIdentity.slotOf(messageId, slots) != expected
                                    || key != null && OutboxShardIdentity.slotOf(key, slots) != expected) {
                                throw new IllegalStateException("OUTBOX_SHARD_DATA_MISMATCH: " + shard.getKey());
                            }
                        }
                    }
                } finally {
                    connection.rollback();
                }
            } catch (SQLException | IllegalArgumentException failure) {
                throw new IllegalStateException("OUTBOX_SHARD_DATA_VALIDATION_FAILED: " + shard.getKey(), failure);
            }
        }
    }

    private record ReadinessBO(Map<String, DataSource> primaryDataSources, String routeFingerprint) {
    }

    private static final class SchemaScopedDataSource extends AbstractDataSource {

        private final DataSource delegate;
        private final String schema;

        private SchemaScopedDataSource(DataSource delegate, String schema) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.schema = Objects.requireNonNull(schema, "schema");
        }

        @Override
        public Connection getConnection() throws SQLException {
            return scope(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return scope(delegate.getConnection(username, password));
        }

        private Connection scope(Connection connection) throws SQLException {
            String originalSchema;
            try {
                originalSchema = connection.getSchema();
                if (originalSchema == null || originalSchema.isBlank()) {
                    throw new SQLException("OUTBOX_DDL_ORIGINAL_SCHEMA_UNKNOWN");
                }
                connection.setSchema(schema);
            } catch (SQLException failure) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }

            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "OutboxSchemaScopedConnection[" + schema + ']';
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == arguments[0];
                                default -> throw new UnsupportedOperationException(method.getName());
                            };
                        }
                        if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                            closeScoped(connection, originalSchema, closed);
                            return null;
                        }
                        if ("unwrap".equals(method.getName()) && method.getParameterCount() == 1) {
                            Class<?> type = (Class<?>) arguments[0];
                            if (type.isInstance(proxy)) {
                                return proxy;
                            }
                            throw new SQLException("OUTBOX_DDL_CONNECTION_UNWRAP_UNSUPPORTED");
                        }
                        if ("isWrapperFor".equals(method.getName()) && method.getParameterCount() == 1) {
                            return ((Class<?>) arguments[0]).isInstance(proxy);
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private static void closeScoped(Connection connection, String originalSchema, AtomicBoolean closed)
                throws SQLException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            SQLException failure = null;
            try {
                if (!connection.isClosed()) {
                    connection.setSchema(originalSchema);
                }
            } catch (SQLException restoreFailure) {
                failure = restoreFailure;
            }
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
