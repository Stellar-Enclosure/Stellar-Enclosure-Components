package top.egon.cola.component.common.mybatis.extension;

import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.mapping.MappedStatement;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;

import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Synchronous capability for one explicitly declared fixed-zero Mapper/model/table boundary. */
public final class EgonColaTechnicalTenantScope {

    private static final Pattern QUALIFIED_TABLE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");

    private static final ThreadLocal<ScopeVO> CURRENT = new ThreadLocal<>();

    private EgonColaTechnicalTenantScope() {
    }

    /** Binds only the capability; trusted caller context must independently supply MDC tenant zero. */
    public static <T> T execute(Class<?> mapperType, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        ScopeVO selected = descriptor(mapperType);
        ScopeVO previous = CURRENT.get();
        if (previous != null && !previous.equals(selected)) {
            throw new IllegalArgumentException("Technical tenant scope cannot change Mapper while nested");
        }
        CURRENT.set(selected);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Applies to scalar/empty-result statements as well as model-bearing statements. */
    public static void assertStatementScope(MappedStatement statement) {
        ScopeVO declared = statementDescriptor(statement);
        if (declared == null) {
            return;
        }
        if (!declared.equals(CURRENT.get())) {
            throw scopeRequired();
        }
        if (EgonColaTenantIdProvider.currentTenantId() != 0L) {
            throw new IllegalStateException("TENANT_CONTEXT_MISMATCH");
        }
    }

    /** Preserves ordinary positive tenants while binding every zero model to its exact Mapper. */
    public static void assertModelTenant(Class<?> modelType, Long tenantId, MappedStatement statement) {
        Objects.requireNonNull(modelType, "modelType");
        if (tenantId == null) {
            return; // Full Jakarta validation and the existing context equality remain authoritative.
        }
        if (tenantId < 0) {
            throw new IllegalStateException("TENANT_ID_INVALID");
        }
        ScopeVO active = CURRENT.get();
        ScopeVO declared = statementDescriptor(statement);
        if (tenantId > 0) {
            if (active != null && active.modelType() == modelType
                    || declared != null && declared.modelType() == modelType) {
                throw new IllegalStateException("TECHNICAL_TENANT_FIXED_ZERO_REQUIRED");
            }
            return;
        }
        if (active == null || active.modelType() != modelType
                || !active.mapperType().getName().equals(namespace(statement))
                || declared == null || !active.equals(declared)
                || !active.table().equals(modelTable(modelType))) {
            throw scopeRequired();
        }
    }

    /** Distinguishes the explicit technical capability from legacy scalar tenant policy. */
    public static boolean requiresScope(MappedStatement statement) {
        return CURRENT.get() != null || statementDescriptor(statement) != null;
    }

    /** Checks every raw SQL table, including ignored tables which do not participate in normal routing. */
    public static void assertSqlTable(MappedStatement statement, String schema, String table, boolean ignored) {
        if (!requiresScope(statement)) {
            if (ignored && zeroContext()) { throw scopeRequired(); }
            return;
        }
        ScopeVO scope = registeredScope(statement);
        String[] qualified = scope.table().split("\\.");
        if (!qualified[1].equals(table) || schema != null && !qualified[0].equals(schema)
                || ignored && schema == null) {
            throw new IllegalStateException("TECHNICAL_TENANT_TABLE_MISMATCH");
        }
    }

    /** Resolves only a declared fixed-zero SINGLE address, without weakening positive-tenant strategies. */
    public static EgonColaPhysicalTargetBO resolveSingleTarget(MappedStatement statement,
            EgonColaRoutingProfileBO profile, String schema, String table) {
        ScopeVO scope = registeredScope(statement);
        assertSqlTable(statement, schema, table, false);
        if (profile == null || profile.kind() != EgonColaRoutingProfileBO.TableKindEnum.SINGLE
                || !table.equals(profile.logicalTable()) || profile.tenantSlotCount() != 1
                || profile.secondaryBucketCount() != 1 || profile.actualNodes().size() != 1) {
            throw new IllegalStateException("TECHNICAL_TENANT_SINGLE_REQUIRED");
        }
        var nodes = profile.actualNodes().get(new EgonColaRoutingProfileBO.PartitionKeyBO(0, 0));
        if (nodes == null || nodes.size() != 1
                || !(nodes.getFirst().schema() + '.' + nodes.getFirst().table()).equals(scope.table())) {
            throw new IllegalStateException("TECHNICAL_TENANT_SINGLE_REQUIRED");
        }
        return nodes.getFirst();
    }

    private static ScopeVO registeredScope(MappedStatement statement) {
        ScopeVO scope = CURRENT.get();
        if (scope == null || statement == null || statement.getConfiguration() == null
                || !statement.getConfiguration().getMapperRegistry().hasMapper(scope.mapperType())
                || !scope.equals(statementDescriptor(statement))) {
            throw scopeRequired();
        }
        assertStatementScope(statement);
        return scope;
    }

    private static boolean zeroContext() {
        try { return EgonColaTenantIdProvider.currentTenantId() == 0L; }
        catch (IllegalStateException absentOrMalformed) {
            // Normal guards still validate non-ignored SQL; this preserves existing ignored plumbing without MDC.
            return false;
        }
    }

    private static ScopeVO statementDescriptor(MappedStatement statement) {
        String namespace = namespace(statement);
        if (namespace == null) {
            return null;
        }
        ScopeVO current = CURRENT.get();
        if (current != null && current.mapperType().getName().equals(namespace)
                && statement.getConfiguration() != null
                && statement.getConfiguration().getMapperRegistry().hasMapper(current.mapperType())) {
            return current;
        }
        if (statement.getConfiguration() != null) {
            for (Class<?> mapperType : statement.getConfiguration().getMapperRegistry().getMappers()) {
                if (mapperType.getName().equals(namespace)) {
                    EgonColaExplicitTenantScopeMapper marker =
                            mapperType.getDeclaredAnnotation(EgonColaExplicitTenantScopeMapper.class);
                    return isTechnical(marker) ? descriptor(mapperType) : null;
                }
            }
        }
        // Real configured Mappers above take precedence. This also supports direct statement fixtures
        // and cannot manufacture a capability from an unregistered namespace without an active scope.
        ScopeVO active = CURRENT.get();
        return active != null && active.mapperType().getName().equals(namespace) ? active : null;
    }

    private static ScopeVO descriptor(Class<?> mapperType) {
        if (mapperType == null || !mapperType.isInterface()) {
            throw new IllegalArgumentException("Technical tenant scope requires a Mapper interface");
        }
        EgonColaExplicitTenantScopeMapper marker =
                mapperType.getDeclaredAnnotation(EgonColaExplicitTenantScopeMapper.class);
        if (!isTechnical(marker) || marker.technicalModel() == Void.class
                || !EgonModel.class.isAssignableFrom(marker.technicalModel())
                || Modifier.isAbstract(marker.technicalModel().getModifiers())
                || !QUALIFIED_TABLE.matcher(marker.technicalTable()).matches()
                || !marker.technicalTable().equals(modelTable(marker.technicalModel()))) {
            throw new IllegalArgumentException("Technical tenant Mapper must declare its exact model and schema.table");
        }
        return new ScopeVO(mapperType, marker.technicalModel(), marker.technicalTable());
    }

    private static boolean isTechnical(EgonColaExplicitTenantScopeMapper marker) {
        return marker != null && (marker.technicalModel() != Void.class || !marker.technicalTable().isEmpty());
    }

    private static String modelTable(Class<?> modelType) {
        TableName table = modelType.getDeclaredAnnotation(TableName.class);
        if (table == null) {
            return "";
        }
        return table.schema().isEmpty() ? table.value() : table.schema() + '.' + table.value();
    }

    private static String namespace(MappedStatement statement) {
        String id = statement == null ? null : statement.getId();
        if (id == null) {
            return null;
        }
        int separator = id.lastIndexOf('.');
        return separator > 0 && separator < id.length() - 1 ? id.substring(0, separator) : null;
    }

    private static IllegalStateException scopeRequired() {
        return new IllegalStateException("TECHNICAL_TENANT_SCOPE_REQUIRED");
    }

    private record ScopeVO(Class<?> mapperType, Class<?> modelType, String table) { }
}
