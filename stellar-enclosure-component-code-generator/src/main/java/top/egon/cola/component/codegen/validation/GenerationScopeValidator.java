package top.egon.cola.component.codegen.validation;

import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.model.CodegenSchemaBO;
import top.egon.cola.component.codegen.profile.ProjectLayoutStrategy;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Preflight for persistence generation. A failure returns diagnostics and does not create directories.
 */
@Slf4j
public class GenerationScopeValidator {

    public static final String BASE_FIELD_TYPE = "BASE_FIELD_TYPE";

    public static final String LEGACY_UNIQUE_KEY = "LEGACY_UNIQUE_KEY";

    public static final String UNSUPPORTED_ID = "UNSUPPORTED_ID";

    public static final String UNKNOWN_SQL_TYPE = "UNKNOWN_SQL_TYPE";

    public static final String MISSING_TYPE = "MISSING_TYPE";

    public static final String BROADCAST_WRITE = "BROADCAST_WRITE";

    public static final String UNSUPPORTED_ARTIFACT = "UNSUPPORTED_ARTIFACT";

    private static final Set<String> BASE_COLUMNS = Set.of(
            "id", "tenant_id", "create_user_id", "create_time", "update_user_id", "update_time", "deleted_at", "version");

    private static final List<String> XML_COLUMNS = List.of(
            "id", "tenant_id", "create_user_id", "create_time", "update_user_id", "update_time", "deleted_at", "version");

    private static final String PROPERTIES_BEAN =
            "egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties";

    public List<CodegenPlanBO.DiagnosticBO> validate(CodegenConfigBO config, CodegenSchemaBO schema) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (config == null || config.getProfile() == null || config.getArtifacts() == null || config.getArtifacts().isEmpty()) {
            diagnostics.add(diagnostic("CONFIG_REQUIRED", "/artifacts", "generation scope is incomplete"));
            return diagnostics;
        }
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        for (String artifact : config.getArtifacts()) {
            if (!layout.allows(artifact)) {
                diagnostics.add(diagnostic(UNSUPPORTED_ARTIFACT, "/artifacts", "artifact is not allowed for " + config.getProfile().code()));
            }
        }
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            diagnostics.add(diagnostic("CONFIG_REQUIRED", "/logicalTables", "schema has no tables"));
            return diagnostics;
        }
        for (CodegenSchemaBO.TableBO table : schema.getTables()) {
            diagnostics.addAll(validateTable(config, table));
            diagnostics.addAll(validateEvents(config, table));
        }
        diagnostics.addAll(existingTypes(config));
        if (!diagnostics.isEmpty()) {
            log.warn("generation scope rejected: {}", diagnostics.stream().map(CodegenPlanBO.DiagnosticBO::getCode).distinct().toList());
        }
        return List.copyOf(diagnostics);
    }

    public Map<String, Object> renderModel(CodegenConfigBO config, CodegenSchemaBO.TableBO table, String artifact) {
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        for (CodegenSchemaBO.ColumnBO column : table.getColumns()) {
            TemporalDefaultValidator.requireUtc(column);
        }
        String poType = simpleType(config, "po", typeName(table.getLogicalName(), "PO"));
        String daoType = simpleType(config, "dao", typeName(table.getLogicalName(), "DAO"));
        String repoType = typeName(table.getLogicalName(), "Repository");
        String poFqn = qualified(config, "po", layout.packageName(config, "po"), poType);
        String daoFqn = qualified(config, "dao", layout.packageName(config, "dao"), daoType);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("artifact", artifact);
        model.put("poPackage", packageOf(poFqn));
        model.put("daoPackage", packageOf(daoFqn));
        model.put("repoPackage", layout.packageName(config, "repo"));
        model.put("poType", poType);
        model.put("daoType", daoType);
        model.put("repoType", repoType);
        model.put("poFqn", poFqn);
        model.put("daoFqn", daoFqn);
        model.put("tableName", table.getLogicalName());
        model.put("businessFields", businessFields(table));
        model.put("xmlColumns", xmlColumns(table));
        String stem = typeName(table.getLogicalName(), "");
        String domainType = stem + "BO";
        String domainPackage = layout.packageName(config, "domain-model");
        CodegenConfigBO.FieldPoliciesBO policies = config.getFieldPolicies();
        model.put("daoBean", decapitalize(daoType));
        model.put("repoBean", decapitalize(repoType));
        model.put("propertiesBean", PROPERTIES_BEAN);
        model.put("stem", stem);
        model.put("domainType", domainType);
        model.put("domainPackage", domainPackage);
        model.put("domainFqn", domainPackage + "." + domainType);
        model.put("domainQueryType", stem + "DomainQuery");
        model.put("domainQueryFqn", domainPackage + "." + stem + "DomainQuery");
        model.put("domainServiceType", stem + "DomainService");
        model.put("domainServicePackage", layout.packageName(config, "domain-service"));
        model.put("domainServiceFqn", layout.packageName(config, "domain-service") + "." + stem + "DomainService");
        model.put("domainImplType", stem + "DomainServiceImpl");
        model.put("domainImplPackage", layout.packageName(config, "domain-impl"));
        model.put("applicationPackage", layout.packageName(config, "command"));
        model.put("commandPackage", layout.packageName(config, "command"));
        model.put("queryPackage", layout.packageName(config, "query"));
        model.put("resultPackage", layout.packageName(config, "result"));
        model.put("managePackage", layout.packageName(config, "manage"));
        model.put("manageImplPackage", layout.packageName(config, "manage-impl"));
        model.put("commandExecutorPackage", layout.packageName(config, "command-executor"));
        model.put("queryExecutorPackage", layout.packageName(config, "query-executor"));
        model.put("adapterDtoPackage", layout.packageName(config, "adapter-dto"));
        model.put("adapterVoPackage", layout.packageName(config, "adapter-vo"));
        model.put("adapterConverterPackage", layout.packageName(config, "adapter-converter"));
        model.put("createCommandType", "Create" + stem + "Command");
        model.put("updateCommandType", "Update" + stem + "Command");
        model.put("deleteCommandType", "Delete" + stem + "Command");
        model.put("detailQueryType", stem + "DetailQuery");
        model.put("pageQueryType", stem + "PageQuery");
        model.put("createExecutorType", "Create" + stem + "CmdExe");
        model.put("updateExecutorType", "Update" + stem + "CmdExe");
        model.put("deleteExecutorType", "Delete" + stem + "CmdExe");
        model.put("detailExecutorType", stem + "DetailQueryExe");
        model.put("pageExecutorType", stem + "PageQueryExe");
        model.put("resultType", stem + "Result");
        model.put("manageType", stem + "Manage");
        model.put("manageImplType", stem + "ManageImpl");
        model.put("manageBean", decapitalize(stem + "Manage"));
        model.put("domainServiceBean", decapitalize(stem + "DomainService"));
        model.put("controllerType", stem + "Controller");
        model.put("controllerBean", decapitalize(stem + "Controller"));
        model.put("controllerPackage", layout.packageName(config, "controller"));
        model.put("createDtoType", "Create" + stem + "DTO");
        model.put("updateDtoType", "Update" + stem + "DTO");
        model.put("pageDtoType", stem + "PageDTO");
        model.put("viewType", stem + "VO");
        model.put("adapterConverterType", stem + "AdapterConverter");
        model.put("persistenceConverterType", stem + "PersistenceConverter");
        model.put("persistenceConverterPackage", layout.packageName(config, "persistence-converter"));
        model.put("persistenceConverterFqn", layout.packageName(config, "persistence-converter") + "." + stem + "PersistenceConverter");
        model.put("persistenceConverterBean", decapitalize(stem + "PersistenceConverterImpl"));
        model.put("createConverterType", "Create" + stem + "CommandConverter");
        model.put("updateConverterType", "Update" + stem + "CommandConverter");
        model.put("deleteConverterType", "Delete" + stem + "CommandConverter");
        model.put("resultConverterType", stem + "ResultConverter");
        model.put("pageConverterType", stem + "PageQueryConverter");
        model.put("applicationConverterPackage", layout.packageName(config, "application-converter"));
        model.put("domainFields", policyFields(table, unionPolicies(policies)));
        model.put("createFields", policyFields(table, list(policies, "create")));
        model.put("updateFields", policyFields(table, list(policies, "update")));
        model.put("createColumns", list(policies, "create"));
        model.put("updateColumns", list(policies, "update"));
        model.put("resultFields", policyFields(table, list(policies, "result")));
        model.put("filterFields", policyFields(table, list(policies, "filter")));
        model.put("sortFields", policyFields(table, list(policies, "sort")));
        model.put("includeQuery", config.getArtifacts() != null && config.getArtifacts().contains("domain-query"));
        model.put("errorMapper", config.getApiContract() == null ? "" : config.getApiContract().getExistingErrorMapper());
        model.put("apiBasePath", config.getApiContract() == null || config.getApiContract().getBasePath() == null
                ? "" : config.getApiContract().getBasePath());
        model.put("ignoredPoFields", List.of(
                "tenantId", "createUserId", "createTime", "updateUserId", "updateTime", "deletedAt"));
        return Map.copyOf(model);
    }

    public Map<String, Object> eventModel(CodegenConfigBO config, CodegenSchemaBO.TableBO table,
                                          CodegenConfigBO.EventDefinitionBO event) {
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        Map<String, Object> model = new LinkedHashMap<>(renderModel(config, table, "command"));
        String stem = typeName(table.getLogicalName(), "");
        String action = event.getAction();
        model.put("eventPackage", layout.packageName(config, "event"));
        model.put("eventExecutorPackage", layout.packageName(config, "event-executor"));
        model.put("eventConverterPackage", layout.packageName(config, "event-converter"));
        model.put("eventHandlerPackage", layout.packageName(config, "event-handler"));
        model.put("eventType", event.getName());
        model.put("eventExecutorType", event.getName() + "Exe");
        model.put("eventConverterType", event.getName() + "Converter");
        model.put("eventHandlerType", event.getName() + "Handler");
        model.put("eventSchemaVersion", event.getSchemaVersion());
        model.put("eventCommandType", Character.toUpperCase(action.charAt(0)) + action.substring(1) + stem + "Command");
        model.put("eventCommandExecutorType", Character.toUpperCase(action.charAt(0)) + action.substring(1) + stem + "CmdExe");
        model.put("idempotencyType", event.getIdempotencyType());
        model.put("idempotencyBean", event.getIdempotencyBean());
        model.put("idempotencyMethod", event.getIdempotencyMethod());
        List<Map<String, String>> fields = new ArrayList<>();
        for (Map<String, String> target : eventTargets(config, table, action)) {
            Map<String, String> field = new LinkedHashMap<>(target);
            field.put("source", event.getFieldMappings().get(target.get("target")));
            fields.add(Map.copyOf(field));
        }
        model.put("eventFields", List.copyOf(fields));
        return Map.copyOf(model);
    }

    private static List<CodegenPlanBO.DiagnosticBO> validateEvents(CodegenConfigBO config, CodegenSchemaBO.TableBO table) {
        if (config.getEvents() == null || !config.getEvents().isEnabled()
                || config.getEvents().getDefinitions() == null) {
            return List.of();
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        for (CodegenConfigBO.EventDefinitionBO event : config.getEvents().getDefinitions()) {
            if (event == null || !table.getLogicalName().equals(event.getTable()) || event.getFieldMappings() == null
                    || event.getAction() == null || !Set.of("create", "update", "delete").contains(event.getAction())) {
                continue;
            }
            if (!"delete".equals(event.getAction()) && list(config.getFieldPolicies(), event.getAction()).stream()
                    .anyMatch(name -> name == null || BASE_COLUMNS.contains(name) || column(table, name) == null)) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/fieldPolicies/" + event.getAction(),
                        "event command fields must be existing business columns"));
                continue;
            }
            List<Map<String, String>> targets = eventTargets(config, table, event.getAction());
            if (event.getIdempotencyType() != null && event.getName() != null) {
                String guard = event.getIdempotencyType().substring(event.getIdempotencyType().lastIndexOf('.') + 1);
                String commandExecutor = Character.toUpperCase(event.getAction().charAt(0))
                        + event.getAction().substring(1) + typeName(table.getLogicalName(), "") + "CmdExe";
                if (Set.of(event.getName(), event.getName() + "Converter", event.getName() + "Exe", commandExecutor,
                        "Valid", "NotNull", "Qualifier", "Service", "Validated", "Transactional",
                        "RequiredArgsConstructor", "Autowired", "Slf4j").contains(guard)) {
                    diagnostics.add(diagnostic("CONFIG_REQUIRED", "/events/definitions/idempotencyType",
                            "idempotency type conflicts with an event executor import"));
                    continue;
                }
            }
            Set<String> expected = new java.util.LinkedHashSet<>();
            targets.forEach(field -> expected.add(field.get("target")));
            if (expected.size() != targets.size()) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/events/definitions/fieldMappings",
                        "event command fields must be unique"));
                continue;
            }
            if (!expected.equals(event.getFieldMappings().keySet())) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/events/definitions/fieldMappings",
                        "event mappings must cover exactly the selected command fields: " + expected));
                continue;
            }
            Set<String> sources = new java.util.LinkedHashSet<>();
            for (String source : event.getFieldMappings().values()) {
                if (source == null || !source.matches("[a-z][A-Za-z0-9]*")
                        || !SourceVersion.isIdentifier(source) || SourceVersion.isKeyword(source)
                        || "eventId".equals(source) || "schemaVersion".equals(source) || !sources.add(source)) {
                    diagnostics.add(diagnostic("CONFIG_REQUIRED", "/events/definitions/fieldMappings",
                            "event source fields must be unique Java identifiers other than eventId and schemaVersion"));
                    break;
                }
            }
        }
        return diagnostics;
    }

    private static List<Map<String, String>> eventTargets(CodegenConfigBO config, CodegenSchemaBO.TableBO table,
                                                           String action) {
        List<Map<String, String>> targets = new ArrayList<>();
        if (!"create".equals(action)) {
            targets.add(Map.of("target", "id", "javaType", "Long", "nullable", "false", "length", "", "constraint", "positive"));
            targets.add(Map.of("target", "expectedVersion", "javaType", "Long", "nullable", "false", "length", "", "constraint", "nonnegative"));
        }
        if (!"delete".equals(action)) {
            for (Map<String, String> field : policyFields(table, list(config.getFieldPolicies(), action))) {
                targets.add(Map.of("target", field.get("javaName"), "javaType", field.get("javaType"),
                        "nullable", field.get("nullable"), "length", field.get("length"), "constraint", ""));
            }
        }
        return targets;
    }

    private static List<String> unionPolicies(CodegenConfigBO.FieldPoliciesBO policies) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.addAll(list(policies, "create"));
        names.addAll(list(policies, "update"));
        names.addAll(list(policies, "result"));
        names.addAll(list(policies, "filter"));
        names.addAll(list(policies, "sort"));
        return new ArrayList<>(names);
    }

    private static List<String> list(CodegenConfigBO.FieldPoliciesBO policies, String kind) {
        if (policies == null) {
            return List.of();
        }
        List<String> values = switch (kind) {
            case "create" -> policies.getCreate();
            case "update" -> policies.getUpdate();
            case "result" -> policies.getResult();
            case "filter" -> policies.getFilter();
            case "sort" -> policies.getSort();
            default -> List.of();
        };
        return values == null ? List.of() : values;
    }

    private static List<Map<String, String>> policyFields(CodegenSchemaBO.TableBO table, List<String> names) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (String name : names) {
            if (BASE_COLUMNS.contains(name)) {
                continue;
            }
            CodegenSchemaBO.ColumnBO column = column(table, name);
            if (column == null) {
                continue;
            }
            String javaType = javaType(column);
            fields.add(Map.of(
                    "column", column.getName(),
                    "javaName", javaName(column.getName()),
                    "javaType", javaType == null ? "String" : javaType.startsWith("java.") ? javaType : simple(javaType),
                    "nullable", String.valueOf(!Boolean.FALSE.equals(column.getNullable())),
                    "length", column.getLength() == null ? "" : column.getLength().toString()));
        }
        return List.copyOf(fields);
    }

    private static String simple(String javaType) {
        int dot = javaType.lastIndexOf('.');
        return dot < 0 ? javaType : javaType.substring(dot + 1);
    }

    private List<CodegenPlanBO.DiagnosticBO> validateTable(CodegenConfigBO config, CodegenSchemaBO.TableBO table) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (table.getLogicalName() == null || !isIdentifier(table.getLogicalName())) {
            diagnostics.add(diagnostic("CONFIG_REQUIRED", "/logicalTables", "logical table name is not a Java identifier"));
            return diagnostics;
        }
        if ("BROADCAST".equals(table.getRole()) && containsWrite(config.getArtifacts())) {
            diagnostics.add(diagnostic(BROADCAST_WRITE, "/artifacts", "broadcast tables do not generate writes"));
        }
        List<String> primaryColumns = primaryColumns(table);
        CodegenSchemaBO.ColumnBO id = column(table, "id");
        if (primaryColumns.size() != 1 || !"id".equals(primaryColumns.get(0)) || id == null
                || !isBigint(id.getSqlType()) || Boolean.TRUE.equals(id.getNullable())) {
            diagnostics.add(diagnostic(UNSUPPORTED_ID, "/" + table.getLogicalName() + "/id",
                    "CRUD requires one non-null BIGINT id primary key"));
        }
        requireBase(diagnostics, table, "tenant_id", "bigint", false);
        requireBase(diagnostics, table, "create_user_id", "varchar", false);
        requireBase(diagnostics, table, "create_time", "timestamp", false);
        requireBase(diagnostics, table, "update_user_id", "varchar", false);
        requireBase(diagnostics, table, "update_time", "timestamp", false);
        requireBase(diagnostics, table, "deleted_at", "timestamp", true);
        requireBase(diagnostics, table, "version", "bigint", false);
        requireTimeZone(diagnostics, table, "create_time", true);
        requireTimeZone(diagnostics, table, "update_time", true);
        requireTimeZone(diagnostics, table, "deleted_at", false);
        if (hasLegacyBusinessKey(table)) {
            diagnostics.add(diagnostic(LEGACY_UNIQUE_KEY, "/" + table.getLogicalName(),
                    "a business-only unique key still blocks recreate after delete"));
        }
        for (CodegenSchemaBO.ColumnBO column : table.getColumns()) {
            if (column.getName() == null || !isIdentifier(column.getName())) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/" + table.getLogicalName(), "column name is not an identifier"));
                continue;
            }
            if (!TemporalDefaultValidator.supports(column)) {
                diagnostics.add(diagnostic(TemporalDefaultValidator.TIME_DEFAULT_UNSUPPORTED,
                        "/" + table.getLogicalName() + "/" + column.getName(),
                        "temporal default must have explicit UTC semantics or a supported literal"));
            }
            if (!BASE_COLUMNS.contains(column.getName()) && javaType(column) == null) {
                diagnostics.add(diagnostic(UNKNOWN_SQL_TYPE, "/" + table.getLogicalName() + "/" + column.getName(),
                        "SQL type is not mapped"));
            }
        }
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> existingTypes(CodegenConfigBO config) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        Set<String> artifacts = Set.copyOf(config.getArtifacts());
        if (artifacts.contains("repo") && !artifacts.contains("po") && blank(mapping(config, "po"))) {
            diagnostics.add(missing("po"));
        }
        if ((artifacts.contains("repo") || artifacts.contains("mapper-xml")) && !artifacts.contains("dao")
                && blank(mapping(config, "dao"))) {
            diagnostics.add(missing("dao"));
        }
        return diagnostics;
    }

    private static void requireBase(
            List<CodegenPlanBO.DiagnosticBO> diagnostics, CodegenSchemaBO.TableBO table, String name, String typePrefix, boolean nullable) {
        CodegenSchemaBO.ColumnBO column = column(table, name);
        if (column == null || column.getSqlType() == null || !column.getSqlType().toLowerCase(Locale.ROOT).startsWith(typePrefix)
                || (nullable && Boolean.FALSE.equals(column.getNullable()))
                || (!nullable && Boolean.TRUE.equals(column.getNullable()))) {
            diagnostics.add(diagnostic(BASE_FIELD_TYPE, "/" + table.getLogicalName() + "/" + name,
                    "base column does not match EgonModel"));
        }
    }

    private static void requireTimeZone(List<CodegenPlanBO.DiagnosticBO> diagnostics,
                                        CodegenSchemaBO.TableBO table, String name, boolean withTimeZone) {
        CodegenSchemaBO.ColumnBO column = column(table, name);
        if (column == null || column.getSqlType() == null) {
            return;
        }
        String mappedType = javaType(column);
        boolean validTimestamp = "java.time.Instant".equals(mappedType) || "java.time.LocalDateTime".equals(mappedType);
        boolean zoned = "java.time.Instant".equals(mappedType);
        if (!validTimestamp || zoned != withTimeZone) {
            diagnostics.add(diagnostic(BASE_FIELD_TYPE, "/" + table.getLogicalName() + "/" + name,
                    "timestamp timezone does not match EgonModel"));
        }
    }

    private static boolean hasLegacyBusinessKey(CodegenSchemaBO.TableBO table) {
        for (CodegenSchemaBO.ConstraintBO constraint : table.getConstraints()) {
            if (!"UNIQUE".equals(constraint.getKind()) || constraint.getColumns() == null) {
                continue;
            }
            boolean onlyBusiness = !constraint.getColumns().contains("deleted_at")
                    && !constraint.getColumns().contains("id")
                    && containsBusiness(table, constraint.getColumns());
            if (onlyBusiness) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBusiness(CodegenSchemaBO.TableBO table, List<String> columns) {
        if (columns == null) {
            return false;
        }
        for (String column : columns) {
            if (!BASE_COLUMNS.contains(column) && column(table, column) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWrite(List<String> artifacts) {
        for (String artifact : artifacts) {
            if (ProjectLayoutStrategy.PERSISTENCE.contains(artifact)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> primaryColumns(CodegenSchemaBO.TableBO table) {
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
        if (table.getConstraints() != null) {
            for (CodegenSchemaBO.ConstraintBO constraint : table.getConstraints()) {
                if ("PRIMARY_KEY".equals(constraint.getKind()) && constraint.getColumns() != null) {
                    columns.addAll(constraint.getColumns());
                }
            }
        }
        return new ArrayList<>(columns);
    }

    private static List<Map<String, Object>> businessFields(CodegenSchemaBO.TableBO table) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CodegenSchemaBO.ColumnBO column : table.getColumns()) {
            if (column.getName() == null || BASE_COLUMNS.contains(column.getName())) {
                continue;
            }
            String javaType = javaType(column);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("column", column.getName());
            field.put("javaName", javaName(column.getName()));
            field.put("javaType", javaType == null ? "String" : javaType);
            field.put("required", Boolean.FALSE.equals(column.getNullable()));
            field.put("hasDefault", column.getDefaultExpression() != null);
            field.put("length", column.getLength() == null ? "" : column.getLength().toString());
            field.put("enumPattern", enumPattern(table, column.getName()));
            fields.add(Map.copyOf(field));
        }
        return List.copyOf(fields);
    }

    private static String enumPattern(CodegenSchemaBO.TableBO table, String column) {
        if (table.getConstraints() == null) {
            return "";
        }
        java.util.regex.Pattern check = java.util.regex.Pattern.compile(
                "(?i)(?:^|\\()\\s*\\\"?" + java.util.regex.Pattern.quote(column)
                        + "\\\"?\\s+IN\\s*\\(([^)]*)\\)");
        for (CodegenSchemaBO.ConstraintBO constraint : table.getConstraints()) {
            if (!"CHECK".equals(constraint.getKind()) || constraint.getPredicate() == null) {
                continue;
            }
            java.util.regex.Matcher match = check.matcher(constraint.getPredicate());
            if (!match.find()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String value : match.group(1).split(",")) {
                String trimmed = value.trim();
                if (!trimmed.matches("'[A-Za-z0-9_]+'")) {
                    return "";
                }
                values.add(trimmed.substring(1, trimmed.length() - 1));
            }
            if (!values.isEmpty()) {
                return "^(?:" + String.join("|", values) + ")$";
            }
        }
        return "";
    }

    private static List<String> xmlColumns(CodegenSchemaBO.TableBO table) {
        List<String> columns = new ArrayList<>(XML_COLUMNS);
        for (CodegenSchemaBO.ColumnBO column : table.getColumns()) {
            if (column.getName() != null && !columns.contains(column.getName())) {
                columns.add(column.getName());
            }
        }
        return List.copyOf(columns);
    }

    static String javaType(CodegenSchemaBO.ColumnBO column) {
        String type = column.getSqlType() == null ? "" : column.getSqlType().trim()
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("\\s+\\(", "(");
        if (type.startsWith("timestamp") || type.startsWith("date")
                || type.startsWith("time") || type.startsWith("interval")) {
            if (column.getPrecision() == null || (column.getPrecision() >= 0 && column.getPrecision() <= 6)) {
                if (type.matches("(?:timestamp(?:\\([0-6]\\))? with time zone|timestamptz(?:\\([0-6]\\))?)")) {
                    return "java.time.Instant";
                }
                if (type.matches("timestamp(?:\\([0-6]\\))?(?: without time zone)?")) {
                    return "java.time.LocalDateTime";
                }
                if ("date".equals(type)) {
                    return "java.time.LocalDate";
                }
                if (type.matches("time(?:\\([0-6]\\))?(?: without time zone)?")) {
                    return "java.time.LocalTime";
                }
            }
            return null;
        }
        if (type.startsWith("bigint") || "int8".equals(type)) {
            return "Long";
        }
        if (type.startsWith("int") || type.startsWith("integer") || "int4".equals(type)) {
            return "Integer";
        }
        if (type.startsWith("bool")) {
            return "Boolean";
        }
        if (type.startsWith("numeric") || type.startsWith("decimal")) {
            return "java.math.BigDecimal";
        }
        if (type.startsWith("varchar") || type.startsWith("char") || type.startsWith("text") || "bpchar".equals(type)) {
            return "String";
        }
        return null;
    }

    private static String javaName(String column) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (int index = 0; index < column.length(); index++) {
            char current = column.charAt(index);
            if (current == '_') {
                upper = true;
                continue;
            }
            name.append(upper ? Character.toUpperCase(current) : Character.toLowerCase(current));
            upper = false;
        }
        return name.toString();
    }

    static String typeName(String logicalTable, String suffix) {
        StringBuilder name = new StringBuilder();
        boolean upper = true;
        for (int index = 0; index < logicalTable.length(); index++) {
            char current = logicalTable.charAt(index);
            if (current == '_') {
                upper = true;
                continue;
            }
            name.append(upper ? Character.toUpperCase(current) : Character.toLowerCase(current));
            upper = false;
        }
        return name + suffix;
    }

    private static String simpleType(CodegenConfigBO config, String artifact, String generated) {
        if (config.getArtifacts().contains(artifact)) {
            return generated;
        }
        String existing = mapping(config, artifact);
        if (existing == null) {
            return generated;
        }
        int dot = existing.lastIndexOf('.');
        return dot < 0 ? existing : existing.substring(dot + 1);
    }

    private static String qualified(CodegenConfigBO config, String artifact, String generatedPackage, String simple) {
        if (config.getArtifacts().contains(artifact)) {
            return generatedPackage + "." + simple;
        }
        String existing = mapping(config, artifact);
        return existing == null ? generatedPackage + "." + simple : existing;
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String decapitalize(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return typeName;
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }

    private static String mapping(CodegenConfigBO config, String key) {
        return config.getExistingTypes() == null ? null : config.getExistingTypes().get(key);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isBigint(String sqlType) {
        return sqlType != null && sqlType.toLowerCase(Locale.ROOT).startsWith("bigint");
    }

    private static boolean isIdentifier(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static CodegenSchemaBO.ColumnBO column(CodegenSchemaBO.TableBO table, String name) {
        for (CodegenSchemaBO.ColumnBO column : table.getColumns()) {
            if (name.equals(column.getName())) {
                return column;
            }
        }
        return null;
    }

    private static CodegenPlanBO.DiagnosticBO missing(String typeName) {
        CodegenPlanBO.DiagnosticBO diagnostic = diagnostic(MISSING_TYPE, "/existingTypeMappings/" + typeName,
                "missing existing type " + typeName);
        diagnostic.setArtifact(typeName);
        return diagnostic;
    }

    private static CodegenPlanBO.DiagnosticBO diagnostic(String code, String pointer, String message) {
        return CodegenPlanBO.DiagnosticBO.of(code, pointer, message);
    }
}
