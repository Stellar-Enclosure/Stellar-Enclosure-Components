package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.broadcast.yaml.config.YamlBroadcastRuleConfiguration;
import org.apache.shardingsphere.driver.yaml.YamlJDBCConfiguration;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;
import org.apache.shardingsphere.readwritesplitting.yaml.config.YamlReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.sharding.yaml.config.YamlShardingRuleConfiguration;
import org.apache.shardingsphere.single.yaml.config.YamlSingleRuleConfiguration;
import org.apache.shardingsphere.transaction.yaml.config.YamlTransactionRuleConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.TableKindEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.resolver.EgonColaShardingWriteTargetResolver;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardAlgorithm;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardSlots;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyNodes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.Locale;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Validates one typed ShardingSphere policy and materializes routing profiles.
 */
@Slf4j
@Component("egonColaShardingTopologyValidator")
@RequiredArgsConstructor
public class EgonColaShardingTopologyValidator {

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    public TopologyBO validate(EgonColaShardingProperties properties, byte[] yaml) {
        validationUtils.validate(properties);
        if (yaml == null || yaml.length == 0) {
            throw new EgonColaMybatisPlusConfigurationException("SHARDING_YAML_REQUIRED");
        }
        YamlJDBCConfiguration configuration = YamlEngine.unmarshal(
                new String(yaml, StandardCharsets.UTF_8), YamlJDBCConfiguration.class);
        if (!configuration.getDataSources().isEmpty()) {
            throw new EgonColaMybatisPlusConfigurationException("PHYSICAL_DATASOURCES_MUST_HAVE_ONE_OWNER");
        }
        if (configuration.getTransaction() == null) {
            configuration.setTransaction(new YamlTransactionRuleConfiguration());
        }
        String defaultType = configuration.getTransaction().getDefaultType();
        if (defaultType == null || defaultType.isBlank()) {
            configuration.getTransaction().setDefaultType(properties.getTransactionDefaultType());
            defaultType = properties.getTransactionDefaultType();
        }
        if (!"LOCAL".equals(defaultType) && !"XA".equals(defaultType)) {
            throw new EgonColaMybatisPlusConfigurationException("LOCAL_TRANSACTION_REQUIRED");
        }
        Map<String, String> schemas = new TreeMap<>();
        for (PhysicalDataSourceProperties source : properties.getDataSources()) {
            if (source.role() == DataSourceRoleEnum.PRIMARY) {
                schemas.putIfAbsent(source.logicalName(), "public");
            }
        }
        Map<String, EgonColaRoutingProfileBO> profiles = new TreeMap<>();
        Set<String> broadcast = new LinkedHashSet<>();
        if (configuration.getRules() != null) {
            for (Object rule : configuration.getRules()) {
                if (rule instanceof YamlSingleRuleConfiguration single) {
                    addSingle(single, schemas, profiles);
                } else if (rule instanceof YamlBroadcastRuleConfiguration copies) {
                    addBroadcast(copies, schemas, profiles, broadcast);
                } else if (rule instanceof YamlShardingRuleConfiguration sharding) {
                    addSharding(sharding, schemas, profiles);
                } else if (rule instanceof YamlReadwriteSplittingRuleConfiguration) {
                    continue;
                } else {
                    throw new EgonColaMybatisPlusConfigurationException("UNSUPPORTED_SHARDING_RULE");
                }
            }
        }
        if (properties.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE) {
            validateReadwrite(properties, configuration, profiles);
        }
        if (profiles.isEmpty() && properties.getConfigStyle()
                == EgonColaShardingProperties.ConfigStyleEnum.STRATEGY
                && (properties.getTables() == null || properties.getTables().isEmpty())) {
            throw new EgonColaMybatisPlusConfigurationException("INVALID_TOPOLOGY");
        }
        String fingerprint = fingerprint(profiles, schemas);
        return new TopologyBO(YamlEngine.marshal(configuration).getBytes(StandardCharsets.UTF_8),
                profiles, schemas, broadcast, fingerprint);
    }

    /** Uses the same canonical order for the current topology and verified additive predecessors. */
    public static String fingerprint(Map<String, EgonColaRoutingProfileBO> profiles, Map<String, String> schemas) {
        return digest("ss-policy-v1|" + new TreeMap<>(profiles) + '|' + new TreeMap<>(schemas));
    }

    private static void validateReadwrite(EgonColaShardingProperties properties, YamlJDBCConfiguration configuration,
                                          Map<String, EgonColaRoutingProfileBO> profiles) {
        if (configuration.getRules() == null) {
            throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
        }
        if (profiles.isEmpty()) {
            throw new EgonColaMybatisPlusConfigurationException("INVALID_TOPOLOGY");
        }
        Map<String, List<PhysicalDataSourceProperties>> groups = new TreeMap<>();
        Set<String> names = new HashSet<>();
        Map<String, String> groupNames = new HashMap<>();
        for (PhysicalDataSourceProperties source : properties.getDataSources()) {
            if (!names.add(source.name().toLowerCase(Locale.ROOT))) {
                throw new EgonColaMybatisPlusConfigurationException("DUPLICATE_PHYSICAL_DATASOURCE");
            }
            String previousName = groupNames.putIfAbsent(source.logicalName().toLowerCase(Locale.ROOT), source.logicalName());
            if (previousName != null && !previousName.equals(source.logicalName())) {
                throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
            }
            groups.computeIfAbsent(source.logicalName(), ignored -> new ArrayList<>()).add(source);
        }
        for (var source : properties.getDataSources()) {
            String groupWithSameName = groupNames.get(source.name().toLowerCase(Locale.ROOT));
            if (groupWithSameName != null && !groupWithSameName.equals(source.logicalName())) {
                throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
            }
        }
        List<YamlReadwriteSplittingRuleConfiguration> rules = configuration.getRules().stream()
                .filter(YamlReadwriteSplittingRuleConfiguration.class::isInstance)
                .map(YamlReadwriteSplittingRuleConfiguration.class::cast).toList();
        if (rules.size() != 1 || !rules.getFirst().getDataSourceGroups().keySet().equals(groups.keySet())) {
            throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
        }
        groups.forEach((name, sources) -> {
            List<String> primaries = sources.stream().filter(source -> source.role() == DataSourceRoleEnum.PRIMARY)
                    .map(PhysicalDataSourceProperties::name).toList();
            List<String> replicas = sources.stream().filter(source -> source.role() == DataSourceRoleEnum.REPLICA)
                    .map(PhysicalDataSourceProperties::name).toList();
            var rule = rules.getFirst().getDataSourceGroups().get(name);
            if (primaries.size() != 1 || replicas.isEmpty() || rule == null
                    || !primaries.getFirst().equals(rule.getWriteDataSourceName())
                    || rule.getReadDataSourceNames() == null
                    || !new HashSet<>(replicas).equals(new HashSet<>(rule.getReadDataSourceNames()))
                    || rule.getReadDataSourceNames().size() != replicas.size()
                    || !"DYNAMIC".equals(rule.getTransactionalReadQueryStrategy())) {
                throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
            }
        });
        profiles.values().stream().flatMap(profile -> profile.actualNodes().values().stream())
                .flatMap(List::stream).forEach(node -> {
                    if (!groups.containsKey(node.group())) {
                        throw new EgonColaMybatisPlusConfigurationException("READWRITE_GROUP_MISMATCH");
                    }
                });
        validateGuardAlgorithms(configuration, profiles);
    }

    private static void validateGuardAlgorithms(YamlJDBCConfiguration configuration,
                                                Map<String, EgonColaRoutingProfileBO> profiles) {
        for (Object rule : configuration.getRules()) {
            if (rule instanceof YamlShardingRuleConfiguration sharding && sharding.getTables() != null) {
                sharding.getTables().forEach((table, configured) -> {
                    EgonColaRoutingProfileBO profile = profiles.get(table);
                    var database = configured.getDatabaseStrategy();
                    if (database == null || database.getStandard() == null || sharding.getShardingAlgorithms() == null) {
                        throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                    }
                    var algorithm = sharding.getShardingAlgorithms().get(database.getStandard().getShardingAlgorithmName());
                    if (profile.kind() == TableKindEnum.TECHNICAL_GROUP) {
                        if (algorithm == null || !"CLASS_BASED".equals(algorithm.getType())
                                || !"shard_slot".equals(database.getStandard().getShardingColumn())
                                || configured.getTableStrategy() != null
                                || !EgonColaGroupShardAlgorithm.class.getName().equals(
                                        algorithm.getProps().getProperty("algorithmClassName"))
                                || !"STANDARD".equals(algorithm.getProps().getProperty("strategy"))
                                || !EgonColaGroupShardSlots.encode(profile.tenantSlotMap()).equals(
                                        algorithm.getProps().getProperty("slot-targets"))) {
                            throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                        }
                        return;
                    }
                    if (algorithm == null || !"CLASS_BASED".equals(algorithm.getType())
                            || !"tenant_id".equals(database.getStandard().getShardingColumn())) {
                        throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                    }
                    Properties props = algorithm.getProps();
                    String className = props.getProperty("algorithmClassName");
                    if (profile.kind() == TableKindEnum.TENANT_LEGACY) {
                        List<EgonColaPhysicalTargetBO> nodes = profile.actualNodes().values().stream().flatMap(List::stream).toList();
                        List<String> order = nodes.stream().map(EgonColaPhysicalTargetBO::group).toList();
                        if (!Objects.equals(className, "top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaStandardTenantIdShardingAlgorithm")
                                || new HashSet<>(order).size() != order.size()
                                || !"true".equals(props.getProperty("require-complete-targets"))
                                || !String.join(",", order).equals(props.getProperty("target-order"))) {
                            throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                        }
                        if (configured.getTableStrategy() != null) {
                            var tableStrategy = configured.getTableStrategy().getStandard();
                            var tableAlgorithm = tableStrategy == null ? null : sharding.getShardingAlgorithms().get(tableStrategy.getShardingAlgorithmName());
                            if (tableAlgorithm == null || !"CLASS_BASED".equals(tableAlgorithm.getType())
                                    || !"tenant_id".equals(tableStrategy.getShardingColumn())
                                    || !Objects.equals(className, tableAlgorithm.getProps().getProperty("algorithmClassName"))
                                    || !String.join(",", nodes.stream().map(EgonColaPhysicalTargetBO::table).toList())
                                            .equals(tableAlgorithm.getProps().getProperty("target-order"))) {
                                throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                            }
                        }
                    } else if (profile.kind() == TableKindEnum.TENANT_ID_TWO_LEVEL) {
                        var tableStrategy = configured.getTableStrategy().getComplex();
                        var tableAlgorithm = tableStrategy == null ? null : sharding.getShardingAlgorithms().get(tableStrategy.getShardingAlgorithmName());
                        if (!Objects.equals(className, "top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaLongTenantShardingAlgorithm")
                                || !profile.equals(EgonColaShardingWriteTargetResolver.profile(props))
                                || tableAlgorithm == null || !"CLASS_BASED".equals(tableAlgorithm.getType())
                                || !Objects.equals(tableAlgorithm.getProps().getProperty("algorithmClassName"),
                                        "top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaTenantBusinessComplexShardingAlgorithm")
                                || "tenant_id".equals(profile.secondaryColumn()) || tableStrategy.getShardingColumns() == null
                                || !Set.of("tenant_id", profile.secondaryColumn()).equals(
                                        Arrays.stream(tableStrategy.getShardingColumns().split(","))
                                                .map(String::trim).collect(Collectors.toSet()))
                                || !profile.equals(EgonColaShardingWriteTargetResolver.profile(tableAlgorithm.getProps()))) {
                            throw new EgonColaMybatisPlusConfigurationException("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
                        }
                    }
                });
            }
        }
    }

    private static void addSingle(YamlSingleRuleConfiguration single, Map<String, String> schemas,
                                  Map<String, EgonColaRoutingProfileBO> profiles) {
        if (single.getTables() == null) {
            return;
        }
        for (String table : single.getTables()) {
            String[] parts = table.split("\\.");
            if (parts.length != 3 || table.contains("*")) {
                throw new EgonColaMybatisPlusConfigurationException("SINGLE_NODE_REQUIRED");
            }
            EgonColaPhysicalTargetBO node = new EgonColaPhysicalTargetBO(parts[0], parts[1], parts[2]);
            put(profiles, EgonColaShardingStrategyNodes.simple(parts[2], TableKindEnum.SINGLE, List.of(node)));
        }
    }

    private static void addBroadcast(YamlBroadcastRuleConfiguration copies, Map<String, String> schemas,
                                     Map<String, EgonColaRoutingProfileBO> profiles, Set<String> broadcast) {
        if (copies.getTables() == null) {
            return;
        }
        for (String table : copies.getTables()) {
            List<EgonColaPhysicalTargetBO> nodes = new ArrayList<>();
            schemas.forEach((group, schema) -> nodes.add(new EgonColaPhysicalTargetBO(group, schema, table)));
            put(profiles, EgonColaShardingStrategyNodes.simple(table, TableKindEnum.BROADCAST_READ_ONLY, nodes));
            broadcast.add(table);
        }
    }

    private static void addSharding(YamlShardingRuleConfiguration sharding, Map<String, String> schemas,
                                    Map<String, EgonColaRoutingProfileBO> profiles) {
        if (sharding.getShardingAlgorithms() != null) {
            sharding.getShardingAlgorithms().values().forEach(algorithm -> {
                Properties normalized = new Properties();
                algorithm.getProps().forEach((key, value) -> normalized.setProperty(key.toString(), value.toString()));
                algorithm.setProps(normalized);
            });
        }
        if (sharding.getTables() == null) {
            return;
        }
        sharding.getTables().forEach((table, configured) -> {
            if (configured.getTableStrategy() != null && configured.getTableStrategy().getComplex() != null
                    && configured.getActualDataNodes() != null) {
                Properties props = new Properties();
                if (sharding.getShardingAlgorithms() != null
                        && configured.getTableStrategy().getComplex().getShardingAlgorithmName() != null) {
                    var algorithm = sharding.getShardingAlgorithms()
                            .get(configured.getTableStrategy().getComplex().getShardingAlgorithmName());
                    if (algorithm != null) {
                        props.putAll(algorithm.getProps());
                    }
                }
                props.setProperty("logical-table", table);
                props.setProperty("actual-data-nodes", configured.getActualDataNodes());
                put(profiles, EgonColaShardingWriteTargetResolver.profile(props));
            } else {
                List<EgonColaPhysicalTargetBO> nodes = new ArrayList<>();
                if (configured.getActualDataNodes() != null) {
                    for (String item : configured.getActualDataNodes().split(",")) {
                        String[] parts = item.trim().split("\\.");
                        if (parts.length == 3) {
                            nodes.add(new EgonColaPhysicalTargetBO(parts[0], parts[1], parts[2]));
                        } else if (parts.length == 2) {
                            nodes.add(new EgonColaPhysicalTargetBO(parts[0], schemas.getOrDefault(parts[0], "public"), parts[1]));
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    throw new EgonColaMybatisPlusConfigurationException("INVALID_TOPOLOGY");
                }
                var database = configured.getDatabaseStrategy();
                var standard = database == null ? null : database.getStandard();
                var algorithm = standard == null || sharding.getShardingAlgorithms() == null ? null
                        : sharding.getShardingAlgorithms().get(standard.getShardingAlgorithmName());
                if (algorithm != null && EgonColaGroupShardAlgorithm.class.getName()
                        .equals(algorithm.getProps().getProperty("algorithmClassName"))) {
                    Map<Integer, String> slots = EgonColaGroupShardSlots.decode(algorithm.getProps().getProperty("slot-targets"));
                    if (!slots.equals(EgonColaGroupShardSlots.resolve(
                            nodes.stream().map(EgonColaPhysicalTargetBO::group).toList(), slots.get(0)))
                            || nodes.stream().map(EgonColaPhysicalTargetBO::schema).distinct().count() != 1) {
                        throw new EgonColaMybatisPlusConfigurationException("INVALID_TOPOLOGY");
                    }
                    put(profiles, new EgonColaRoutingProfileBO(table, TableKindEnum.TECHNICAL_GROUP, "group-slot-v1",
                            1, 1, slots, null, null, EgonColaShardingStrategyNodes.SEED,
                            Map.of(new PartitionKeyBO(0, 0), nodes), 1, null));
                } else {
                    put(profiles, new EgonColaRoutingProfileBO(table, TableKindEnum.TENANT_LEGACY, "legacy-v1",
                            1, 1, Map.of(), null, null, EgonColaShardingStrategyNodes.SEED,
                            Map.of(new PartitionKeyBO(0, 0), nodes), 1, null));
                }
            }
        });
    }

    private static void put(Map<String, EgonColaRoutingProfileBO> profiles, EgonColaRoutingProfileBO profile) {
        if (profiles.put(profile.logicalTable(), profile) != null) {
            throw new EgonColaMybatisPlusConfigurationException("LOGICAL_TABLE_RULE_OVERLAP");
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA256_REQUIRED", failure);
        }
    }

    public record TopologyBO(byte[] yaml, Map<String, EgonColaRoutingProfileBO> profiles,
                             Map<String, String> schemas, Set<String> broadcastTables, String fingerprint) {
        public TopologyBO {
            yaml = yaml.clone();
            profiles = Map.copyOf(new TreeMap<>(profiles));
            schemas = Map.copyOf(schemas);
            broadcastTables = Set.copyOf(broadcastTables);
        }

        @Override
        public byte[] yaml() {
            return yaml.clone();
        }
    }
}
