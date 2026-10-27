package top.egon.cola.component.common.mybatis.ddl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.ManagedDdlTarget;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator.TopologyBO;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit opt-in versioned migration of every physical primary before ShardingSphere starts. */
public final class EgonColaManagedDdlInitializer {

    private final EgonColaMybatisPlusProperties.Ddl properties;
    private final EgonColaPostgreDdlRunner runner;
    private final ResourceLoader resources;
    private final ObjectMapper mapper = new ObjectMapper();

    public EgonColaManagedDdlInitializer(EgonColaMybatisPlusProperties.Ddl properties,
                                        EgonColaPostgreDdlRunner runner, ResourceLoader resources) {
        this.properties = properties;
        this.runner = runner;
        this.resources = resources;
    }

    public void initialize(EgonColaShardingProperties sharding, Map<String, DataSource> physical, TopologyBO topology) {
        if (!properties.isAutoMigrate()) {
            return;
        }
        if (!properties.isEnabled() || properties.getTargets() == null || properties.getTargets().isEmpty()) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_MANAGED_TARGETS_REQUIRED");
        }
        Map<String, PhysicalDataSourceProperties> primaries = new HashMap<>();
        for (var source : sharding.getDataSources()) {
            if (source.role() == DataSourceRoleEnum.PRIMARY
                    && primaries.put(source.logicalName(), source) != null) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_GROUP_AMBIGUOUS");
            }
        }
        Set<String> covered = new HashSet<>();
        Set<String> compatibleFingerprints = compatibleFingerprints(topology);
        List<EgonColaDdlTargetBO> targets = new ArrayList<>();
        for (ManagedDdlTarget target : properties.getTargets()) {
            var source = primaries.get(target.logicalName());
            if (source == null || physical.get(source.name()) == null
                    || !covered.add(target.logicalName() + "." + target.schema())) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_TARGET_INVALID");
            }
            targets.add(new EgonColaDdlTargetBO(source.name(), target.schema(), target.role(),
                    physical.get(source.name()), loadManifest(target.manifestResource()), topology.fingerprint(), compatibleFingerprints));
        }
        List<EgonColaPhysicalTargetBO> nodes = topology.profiles().values().stream()
                .flatMap(profile -> profile.actualNodes().values().stream()).flatMap(List::stream).distinct().toList();
        for (var node : nodes) {
            if (!covered.contains(node.group() + "." + node.schema())) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_COVERAGE_INCOMPLETE");
            }
        }
        runner.run(targets);
        verifyPhysicalTables(nodes, primaries, physical);
        if (sharding.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE) {
            awaitReplicaReplay(sharding.getDataSources(), primaries, physical, properties.getTopologyReadyTimeout());
        }
    }

    private Set<String> compatibleFingerprints(TopologyBO topology) {
        Set<String> result = new HashSet<>();
        for (List<String> exclusions : properties.getPreviousRouteExclusions()) {
            if (exclusions == null || exclusions.isEmpty() || new HashSet<>(exclusions).size() != exclusions.size()) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_ROUTE_TRANSITION_INVALID");
            }
            var previous = new TreeMap<>(topology.profiles());
            for (String table : exclusions) {
                if (previous.remove(table) == null) {
                    throw new EgonColaMybatisPlusConfigurationException("DDL_ROUTE_TRANSITION_INVALID");
                }
            }
            result.add(EgonColaShardingTopologyValidator.fingerprint(previous, topology.schemas()));
        }
        return Set.copyOf(result);
    }

    private EgonColaDdlManifestBO loadManifest(String location) {
        if (location == null || !location.matches("classpath:[a-zA-Z0-9_/-]+\\.json") || location.contains("..")) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_MANIFEST_RESOURCE_INVALID");
        }
        Resource resource = resources.getResource(location);
        try (var input = resource.getInputStream()) {
            byte[] content = input.readNBytes(1_048_577);
            if (content.length == 0 || content.length > 1_048_576) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_MANIFEST_RESOURCE_INVALID");
            }
            return mapper.readValue(content, EgonColaDdlManifestBO.class);
        } catch (IOException failure) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_MANIFEST_RESOURCE_INVALID", failure);
        }
    }

    private static void verifyPhysicalTables(List<EgonColaPhysicalTargetBO> nodes,
                                             Map<String, PhysicalDataSourceProperties> primaries,
                                             Map<String, DataSource> physical) {
        for (EgonColaPhysicalTargetBO node : nodes) {
            var primary = primaries.get(node.group());
            if (primary == null) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_TARGET_INVALID");
            }
            try (Connection connection = physical.get(primary.name()).getConnection();
                 var tables = connection.getMetaData().getTables(null, node.schema(), node.table(), new String[]{"TABLE", "PARTITIONED TABLE"})) {
                boolean found = false;
                while (tables.next()) {
                    if (node.schema().equals(tables.getString("TABLE_SCHEM"))
                            && node.table().equals(tables.getString("TABLE_NAME"))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new EgonColaMybatisPlusConfigurationException("DDL_PHYSICAL_TABLE_MISSING",
                            new IllegalStateException(node.group() + "." + node.schema() + "." + node.table()));
                }
            } catch (SQLException failure) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PHYSICAL_TABLE_CHECK_FAILED", failure);
            }
        }
    }

    public static void awaitReplicaReplay(List<PhysicalDataSourceProperties> sources,
                                          Map<String, PhysicalDataSourceProperties> primaries,
                                          Map<String, DataSource> physical, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_TIMEOUT_INVALID");
        }
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_TIMEOUT_INVALID", failure);
        }
        long started = System.nanoTime();
        Map<String, String> positions = new HashMap<>();
        for (var entry : primaries.entrySet()) {
            try (var connection = physical.get(entry.getValue().name()).getConnection();
                 var statement = connection.createStatement()) {
                statement.setQueryTimeout(remainingSeconds(started, timeoutNanos));
                try (var result = statement.executeQuery("SELECT pg_current_wal_lsn()::text")) {
                    if (!result.next() || result.getString(1) == null) {
                        throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_LSN_UNAVAILABLE");
                    }
                    positions.put(entry.getKey(), result.getString(1));
                }
            } catch (SQLException failure) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_PRIMARY_LSN_UNAVAILABLE", failure);
            }
        }
        for (var source : sources) {
            if (source.role() != DataSourceRoleEnum.REPLICA) {
                continue;
            }
            try (var connection = physical.get(source.name()).getConnection();
                 var statement = connection.prepareStatement("SELECT pg_is_in_recovery(), pg_last_wal_replay_lsn() >= ?::pg_lsn")) {
                statement.setString(1, positions.get(source.logicalName()));
                while (true) {
                    statement.setQueryTimeout(remainingSeconds(started, timeoutNanos));
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) {
                            throw new EgonColaMybatisPlusConfigurationException("DDL_REPLICA_ROLE_MISMATCH");
                        }
                        if (result.getBoolean(2)) {
                            break;
                        }
                    }
                    try {
                        Thread.sleep(Math.min(100L, Math.max(1L, timeout.toMillis())));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new EgonColaMybatisPlusConfigurationException("DDL_REPLICA_REPLAY_INTERRUPTED", failure);
                    }
                }
            } catch (SQLException failure) {
                throw new EgonColaMybatisPlusConfigurationException("DDL_REPLICA_REPLAY_FAILED", failure);
            }
        }
    }

    private static int remainingSeconds(long started, long timeoutNanos) {
        long elapsed = System.nanoTime() - started;
        if (elapsed >= timeoutNanos) {
            throw new EgonColaMybatisPlusConfigurationException("DDL_REPLICA_REPLAY_TIMEOUT");
        }
        long remaining = timeoutNanos - elapsed;
        long seconds = remaining / 1_000_000_000L + (remaining % 1_000_000_000L == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds));
    }
}
