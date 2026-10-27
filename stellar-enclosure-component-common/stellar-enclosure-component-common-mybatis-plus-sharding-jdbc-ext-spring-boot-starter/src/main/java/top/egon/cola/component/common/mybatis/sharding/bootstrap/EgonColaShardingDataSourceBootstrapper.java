package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.ddl.EgonColaManagedDdlInitializer;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator.TopologyBO;

import javax.sql.DataSource;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Physical pools → typed policy → logical ShardingSphere DataSource. Closes pools on any failure.
 */
@Slf4j
public class EgonColaShardingDataSourceBootstrapper {

    @Qualifier("egonColaPhysicalDataSourceFactory")
    private final EgonColaPhysicalDataSourceFactory physicalDataSourceFactory;

    @Qualifier("egonColaShardingYamlLoader")
    private final EgonColaShardingYamlLoader yamlLoader;

    @Qualifier("egonColaShardingTopologyValidator")
    private final EgonColaShardingTopologyValidator topologyValidator;

    @Qualifier("egonColaShardingLogicalDataSourceFactory")
    private final LogicalDataSourceFactory logicalDataSourceFactory;

    private final EgonColaManagedDdlInitializer ddlInitializer;

    public EgonColaShardingDataSourceBootstrapper(EgonColaPhysicalDataSourceFactory physicalDataSourceFactory,
            EgonColaShardingYamlLoader yamlLoader, EgonColaShardingTopologyValidator topologyValidator,
            LogicalDataSourceFactory logicalDataSourceFactory) {
        this(physicalDataSourceFactory, yamlLoader, topologyValidator, logicalDataSourceFactory, null);
    }

    public EgonColaShardingDataSourceBootstrapper(EgonColaPhysicalDataSourceFactory physicalDataSourceFactory,
            EgonColaShardingYamlLoader yamlLoader, EgonColaShardingTopologyValidator topologyValidator,
            LogicalDataSourceFactory logicalDataSourceFactory, EgonColaManagedDdlInitializer ddlInitializer) {
        this.physicalDataSourceFactory = physicalDataSourceFactory;
        this.yamlLoader = yamlLoader;
        this.topologyValidator = topologyValidator;
        this.logicalDataSourceFactory = logicalDataSourceFactory;
        this.ddlInitializer = ddlInitializer;
    }

    private volatile TopologyBO readyTopology;

    public DataSource createDataSource(EgonColaShardingProperties properties) {
        Map<String, DataSource> physical = physicalDataSourceFactory.create(properties);
        try {
            byte[] yaml = yamlLoader.load(properties);
            TopologyBO topology = topologyValidator.validate(properties, yaml);
            Map<String, EgonColaRoleGuardedDataSource.ServerIdentity> identities = Map.of();
            if (properties.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE) {
                identities = EgonColaRoleGuardedDataSource.verifyTopology(properties.getDataSources(), physical);
            }
            if (ddlInitializer != null) {
                ddlInitializer.initialize(properties, physical, topology);
            }
            AtomicBoolean guardActive = new AtomicBoolean();
            AtomicReference<DataSource> logicalReference = new AtomicReference<>();
            Map<String, DataSource> routed = physical;
            if (properties.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE) {
                routed = new LinkedHashMap<>();
                for (var source : properties.getDataSources()) {
                    routed.put(source.name(), new EgonColaRoleGuardedDataSource(
                            physical.get(source.name()), source.role(), guardActive, identities.get(source.name()),
                            logicalDataSourceFactory::permitsPrimaryRead, source.jdbcUrl(), source.username(),
                            source.logicalName(), logicalReference::get, logicalDataSourceFactory::enforceSingleWriteGroup));
                }
            }
            DataSource logical = logicalDataSourceFactory.create(routed, topology.yaml());
            DataSource result = properties.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE
                    ? new EgonColaRouteHintGuardedDataSource(logical, logicalDataSourceFactory::validateLogicalSql,
                            logicalDataSourceFactory::enforceSingleWriteGroup) : logical;
            logicalReference.set(result);
            guardActive.set(true);
            readyTopology = topology;
            return result;
        } catch (RuntimeException failure) {
            physicalDataSourceFactory.close(physical.values());
            throw failure;
        } catch (Exception failure) {
            physicalDataSourceFactory.close(physical.values());
            throw new EgonColaMybatisPlusConfigurationException("SHARDING_LOGICAL_STARTUP_FAILED", failure);
        }
    }

    public Map<String, EgonColaRoutingProfileBO> profiles() {
        TopologyBO topology = readyTopology;
        return topology == null ? Map.of() : topology.profiles();
    }

    public String fingerprint() {
        TopologyBO topology = readyTopology;
        if (topology == null) {
            throw new EgonColaMybatisPlusConfigurationException("SHARDING_TOPOLOGY_NOT_READY");
        }
        return topology.fingerprint();
    }

    @FunctionalInterface
    public interface LogicalDataSourceFactory {
        DataSource create(Map<String, DataSource> physical, byte[] yaml) throws Exception;

        /** A component may permit only its own fixed technical reads; ordinary factories deny all reads. */
        default boolean permitsPrimaryRead(net.sf.jsqlparser.statement.select.Select select) {
            return false;
        }

        default void validateLogicalSql(String sql) throws java.sql.SQLException {
        }

        default boolean enforceSingleWriteGroup() {
            return false;
        }
    }
}
