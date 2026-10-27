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
            if (schema != null && config.getInput() != null && "event".equals(config.getInput().getMode())
                    && config.getArtifacts().equals(List.of("event"))) return List.copyOf(diagnostics);
            diagnostics.add(diagnostic("CONFIG_REQUIRED", "/logicalTables", "schema has no tables"));
            return diagnostics;
        }
        for (CodegenSchemaBO.TableBO table : schema.getTables()) {
            diagnostics.addAll(validateTable(config, table));
            diagnostics.addAll(validatePolicies(config, table));
            diagnostics.addAll(validateApiFields(config, table));
            diagnostics.addAll(validateEvents(config, table));
        }
        diagnostics.addAll(existingTypes(config));
        diagnostics.addAll(validateTransport(config));
        if (diagnostics.isEmpty()) {
            for (CodegenSchemaBO.TableBO table : schema.getTables()) diagnostics.addAll(validateDependencies(config, table));
        }
        if (!diagnostics.isEmpty()) {
            log.warn("generation scope rejected: {}", diagnostics.stream().map(CodegenPlanBO.DiagnosticBO::getCode).distinct().toList());
        }
        return List.copyOf(diagnostics);
    }

    private static List<CodegenPlanBO.DiagnosticBO> validateTransport(CodegenConfigBO config) {
        if (!config.getArtifacts().contains("controller") || config.getApiContract() == null) return List.of();
        String mode = config.getApiContract().getTransportMode();
        if (!"generate".equals(mode) && !"existing".equals(mode)) return List.of();
        java.nio.file.Path root = java.nio.file.Path.of(config.getOutputRoot());
        Set<java.nio.file.Path> sources = new java.util.LinkedHashSet<>();
        if (config.getProfile() == top.egon.cola.component.codegen.model.CodegenProfileEnum.LIGHT) {
            sources.add(root.resolve("src/main/java"));
        } else if (config.getRoots() != null) {
            config.getRoots().values().forEach(module -> sources.add(root.resolve(module).resolve("src/main/java")));
        }
        String expectedType = "existing".equals(mode) ? config.getApiContract().getTransportConfigType()
                : config.getBasePackage() + ".adapter.config.CqersPermissionMvcConfiguration";
        String expectedPath = expectedType.replace('.', '/') + ".java";
        int found = 0;
        for (java.nio.file.Path sourceRoot : sources) {
            if (!java.nio.file.Files.isDirectory(sourceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try (var files = java.nio.file.Files.walk(sourceRoot)) {
                for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
                    String source = java.nio.file.Files.readString(file);
                    boolean configurer = source.contains("WebMvcConfigurer") && source.contains("PermissionJsonMapperFactory")
                            && source.matches("(?s).*\\bforTransport\\s*\\(.*") && source.contains("MappingJackson2HttpMessageConverter");
                    boolean configuration = source.matches("(?s).*@(?:[A-Za-z_][A-Za-z0-9_]*\\.)*Configuration\\b.*");
                    if ("existing".equals(mode) && relative.equals(expectedPath) && configurer && configuration) {
                        new OutputPathValidator().checkJavaSource("src/main/java/" + relative,
                                source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        found++;
                        if (found > 1) {
                            return List.of(diagnostic("DUPLICATE_PATH", "/apiContract/transportConfigType", "transport configuration type exists in multiple modules"));
                        }
                    } else if ("generate".equals(mode) && configurer && !relative.equals(expectedPath)) {
                        return List.of(diagnostic("CONFIG_REQUIRED", "/apiContract/transportMode",
                                "an existing permission-aware MVC configuration must be reused instead of duplicated"));
                    }
                }
            } catch (java.io.IOException | OutputPathValidator.PathValidationException failure) {
                return List.of(diagnostic("CONFIG_REQUIRED", "/apiContract/transportConfigType", "MVC transport configuration could not be inspected"));
            }
        }
        return "existing".equals(mode) && found == 0
                ? List.of(diagnostic(MISSING_TYPE, "/apiContract/transportConfigType", "existing permission-aware MVC configuration source is required"))
                : List.of();
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
        CodegenConfigBO.FieldPoliciesBO policies = CodegenConfigValidator.policies(config, table.getLogicalName());
        CodegenConfigBO.DomainMethodsBO methods = config.getDomainMethods() == null ? null : config.getDomainMethods().get(table.getLogicalName());
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
        model.put("createMethod", methods == null || methods.getCreate() == null ? "create" : methods.getCreate());
        model.put("updateMethod", methods == null || methods.getUpdate() == null ? "update" : methods.getUpdate());
        model.put("deleteMethod", methods == null || methods.getDelete() == null ? "delete" : methods.getDelete());
        model.put("detailMethod", methods == null || methods.getDetail() == null ? "detail" : methods.getDetail());
        model.put("queryMethod", methods == null || methods.getQuery() == null ? "query" : methods.getQuery());
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
        List<String> sortColumns = new ArrayList<>(list(policies, "sort"));
        if (!sortColumns.contains("id")) sortColumns.add("id");
        model.put("sortColumns", List.copyOf(sortColumns));
        model.put("includeQuery", config.getArtifacts() != null
                && (config.getArtifacts().contains("domain-query") || config.getArtifacts().contains("domain-impl")));
        model.put("errorMapper", config.getApiContract() == null || config.getApiContract().getExistingErrorMapper() == null
                ? "" : config.getApiContract().getExistingErrorMapper());
        CodegenConfigBO.ApiContractBO api = config.getApiContract();
        CodegenConfigBO.ApiTableBO apiTable = api == null || api.getTables() == null ? null : api.getTables().get(table.getLogicalName());
        model.put("apiBasePath", apiTable != null && !blank(apiTable.getBasePath()) ? apiTable.getBasePath()
                : api == null || api.getBasePath() == null ? "" : api.getBasePath());
        Map<String, Map<String, String>> apiOperations = new LinkedHashMap<>();
        if (apiTable != null && apiTable.getOperations() != null) apiTable.getOperations().forEach((key, value) -> {
            if (value != null) apiOperations.put(key, Map.of("scope", empty(value.getScope()), "permission", empty(value.getPermission()),
                    "resourceCode", empty(value.getResourceCode()),
                    "labelPage", empty(value.getLabelPage()), "labelKey", empty(value.getLabelKey())));
        });
        Map<String, Map<String, String>> apiFields = new LinkedHashMap<>();
        if (apiTable != null && apiTable.getFields() != null) apiTable.getFields().forEach((key, value) -> {
            if (value != null) {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("permission", empty(value.getPermission()));
                metadata.put("labelPage", empty(value.getLabelPage()));
                metadata.put("labelKey", empty(value.getLabelKey()));
                metadata.put("resultKey", empty(value.getResultKey()));
                metadata.put("createKey", empty(value.getCreateKey()));
                metadata.put("updateKey", empty(value.getUpdateKey()));
                metadata.put("createPermission", empty(value.getCreatePermission()));
                metadata.put("updatePermission", empty(value.getUpdatePermission()));
                apiFields.put(key, Map.copyOf(metadata));
            }
        });
        model.put("apiOperations", apiOperations);
        model.put("apiFields", apiFields);
        model.put("permissionReferences", api == null || api.getPermissionReferences() == null ? List.of()
                : api.getPermissionReferences().stream().sorted().toList());
        model.put("permissionTransportBean", api != null && "existing".equals(api.getTransportMode())
                ? empty(api.getTransportBean()) : "cqersPermissionMvcConfiguration");
        Set<String> pagePermissions = new java.util.LinkedHashSet<>();
        if (apiOperations.containsKey("page") && !blank(apiOperations.get("page").get("permission"))) {
            pagePermissions.add(apiOperations.get("page").get("permission"));
        }
        if (apiFields.containsKey("id") && !blank(apiFields.get("id").get("permission"))) {
            pagePermissions.add(apiFields.get("id").get("permission"));
        }
        for (String kind : List.of("filter", "sort")) {
            for (Map<String, String> field : policyFields(table, list(policies, kind))) {
                Map<String, String> permission = apiFields.get(field.get("javaName"));
                if (permission != null && !blank(permission.get("permission"))) pagePermissions.add(permission.get("permission"));
            }
        }
        model.put("pagePermissions", List.copyOf(pagePermissions));
        model.put("ignoredPoFields", List.of(
                "tenantId", "createUserId", "createTime", "updateUserId", "updateTime", "deletedAt"));
        return Map.copyOf(model);
    }

    private static List<CodegenPlanBO.DiagnosticBO> validatePolicies(CodegenConfigBO config, CodegenSchemaBO.TableBO table) {
        CodegenConfigBO.FieldPoliciesBO policies = CodegenConfigValidator.policies(config, table.getLogicalName());
        if (policies == null) return List.of();
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        Set<String> allNames = new java.util.LinkedHashSet<>();
        for (CodegenSchemaBO.ColumnBO value : table.getColumns()) {
            if (value.getName() == null || BASE_COLUMNS.contains(value.getName())) continue;
            String name = javaName(value.getName());
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name) || !allNames.add(name)
                    || Set.of("id", "version").contains(name)) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/" + table.getLogicalName() + "/" + value.getName(),
                        "business columns must map to unique non-reserved Java field names"));
            }
        }
        for (String kind : List.of("create", "update", "result", "filter", "sort")) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (String name : list(policies, kind)) {
                boolean allowedBase = name != null && (("result".equals(kind) && Set.of("id", "version").contains(name))
                        || ("sort".equals(kind) && "id".equals(name)));
                CodegenSchemaBO.ColumnBO value = name == null ? null : column(table, name);
                if (name == null || !seen.add(name) || value == null || (BASE_COLUMNS.contains(name) && !allowedBase)) {
                    diagnostics.add(diagnostic("CONFIG_REQUIRED", "/fieldPolicies/" + kind,
                            "unknown, duplicate or unsupported field for " + table.getLogicalName() + ": " + name));
                    continue;
                }
                String javaName = javaName(name);
                if (("filter".equals(kind) && Set.of("page", "pageNo", "pageSize").contains(javaName))
                        || ("update".equals(kind) && "expectedVersion".equals(javaName))) {
                    diagnostics.add(diagnostic("CONFIG_REQUIRED", "/fieldPolicies/" + kind,
                            "field collides with generated request property: " + javaName));
                }
            }
        }
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> validateApiFields(CodegenConfigBO config, CodegenSchemaBO.TableBO table) {
        if (!config.getArtifacts().contains("controller")) return List.of();
        CodegenConfigBO.ApiContractBO api = config.getApiContract();
        CodegenConfigBO.ApiTableBO entry = api == null || api.getTables() == null ? null : api.getTables().get(table.getLogicalName());
        if (entry == null || entry.getFields() == null) return List.of();
        CodegenConfigBO.FieldPoliciesBO policies = CodegenConfigValidator.policies(config, table.getLogicalName());
        Set<String> output = new java.util.LinkedHashSet<>(List.of("id", "version"));
        Set<String> create = new java.util.LinkedHashSet<>();
        Set<String> update = new java.util.LinkedHashSet<>(List.of("id", "expectedVersion"));
        Set<String> filter = new java.util.LinkedHashSet<>();
        policyFields(table, list(policies, "result")).forEach(field -> output.add(field.get("javaName")));
        policyFields(table, list(policies, "create")).forEach(field -> create.add(field.get("javaName")));
        policyFields(table, list(policies, "update")).forEach(field -> update.add(field.get("javaName")));
        policyFields(table, list(policies, "filter")).forEach(field -> filter.add(field.get("javaName")));
        policyFields(table, list(policies, "sort")).forEach(field -> filter.add(field.get("javaName")));
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        Set<String> required = new java.util.LinkedHashSet<>(output);
        required.addAll(create);
        required.addAll(update);
        required.addAll(filter);
        Set<String> declared = new java.util.LinkedHashSet<>();
        if (api.getPermissionReferences() != null) declared.addAll(api.getPermissionReferences());
        if (api.getLocalPermissions() != null) declared.addAll(api.getLocalPermissions());
        for (String field : required) {
            CodegenConfigBO.ApiFieldBO value = entry.getFields().get(field);
            if (value == null || (output.contains(field) && (blank(value.getResultKey()) || !declared.contains(value.getPermission())))
                    || (create.contains(field) && (blank(value.getCreateKey()) || !declared.contains(value.getCreatePermission())))
                    || (update.contains(field) && (blank(value.getUpdateKey()) || !declared.contains(value.getUpdatePermission())))
                    || (filter.contains(field) && !declared.contains(value.getPermission()))) {
                diagnostics.add(diagnostic("CONFIG_REQUIRED", "/apiContract/tables/" + table.getLogicalName() + "/fields/" + field,
                        "every returned or writable field requires its own resource key and declared permission"));
            }
        }
        return diagnostics;
    }

    public Map<String, Object> eventModel(CodegenConfigBO config, CodegenSchemaBO.TableBO table,
                                          CodegenConfigBO.EventDefinitionBO event) {
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        String action = event.getAction();
        Map<String, Object> model = "delegate".equals(action) ? new LinkedHashMap<>()
                : new LinkedHashMap<>(renderModel(config, table, "command"));
        model.put("eventPackage", layout.packageName(config, "event"));
        model.put("eventExecutorPackage", layout.packageName(config, "event-executor"));
        model.put("eventConverterPackage", layout.packageName(config, "event-converter"));
        model.put("eventHandlerPackage", layout.packageName(config, "event-handler"));
        model.put("eventType", event.getName());
        model.put("eventExecutorType", event.getName() + "Exe");
        model.put("eventConverterType", event.getName() + "Converter");
        model.put("eventHandlerType", event.getName() + "Handler");
        model.put("eventSchemaVersion", event.getSchemaVersion());
        model.put("eventAction", action);
        if ("delegate".equals(action)) {
            model.put("handlerType", event.getHandlerType());
            model.put("handlerBean", event.getHandlerBean());
            model.put("handlerMethod", event.getHandlerMethod());
        } else {
            String stem = typeName(table.getLogicalName(), "");
            model.put("eventCommandType", Character.toUpperCase(action.charAt(0)) + action.substring(1) + stem + "Command");
            model.put("eventCommandExecutorType", Character.toUpperCase(action.charAt(0)) + action.substring(1) + stem + "CmdExe");
        }
        model.put("idempotencyType", event.getIdempotencyType());
        model.put("idempotencyBean", event.getIdempotencyBean());
        model.put("idempotencyMethod", event.getIdempotencyMethod());
        List<Map<String, String>> fields = new ArrayList<>();
        if (!"delegate".equals(action)) {
            for (Map<String, String> target : eventTargets(config, table, action)) {
                Map<String, String> field = new LinkedHashMap<>(target);
                field.put("source", event.getFieldMappings().get(target.get("target")));
                field.put("cascade", "false");
                fields.add(Map.copyOf(field));
            }
        }
        if (event.getFields() != null) {
            for (CodegenConfigBO.EventFieldBO field : event.getFields()) {
                boolean container = eventContainer(field.getJavaType());
                fields.add(Map.of("source", field.getName(), "javaType", container ? cascadeEventType(field.getJavaType(), false) : field.getJavaType(),
                        "nullable", String.valueOf(!field.isRequired()), "length", field.getMaxLength() == null ? "" : field.getMaxLength().toString(),
                        "constraint", "", "target", "", "cascade", String.valueOf(!container && !CodegenConfigValidator.scalarEventType(field.getJavaType()))));
            }
        }
        model.put("eventFields", List.copyOf(fields));
        return Map.copyOf(model);
    }

    private static boolean eventContainer(String type) {
        return type.startsWith("java.util.List<") || type.startsWith("java.util.Set<") || type.startsWith("java.util.Map<String,");
    }

    private static String cascadeEventType(String type, boolean nested) {
        for (String prefix : List.of("java.util.List<", "java.util.Set<", "java.util.Map<String,")) {
            if (type.startsWith(prefix) && type.endsWith(">")) {
                return prefix + cascadeEventType(type.substring(prefix.length(), type.length() - 1), true) + ">";
            }
        }
        String base = type;
        while (base.endsWith("[]")) base = base.substring(0, base.length() - 2);
        if (!nested || CodegenConfigValidator.scalarEventType(base)) return type;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "@Valid " + type : type.substring(0, dot + 1) + "@Valid " + type.substring(dot + 1);
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
            if (!"delete".equals(event.getAction()) && list(CodegenConfigValidator.policies(config, table.getLogicalName()), event.getAction()).stream()
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
            for (Map<String, String> field : policyFields(table, list(CodegenConfigValidator.policies(config, table.getLogicalName()), action))) {
                targets.add(Map.of("target", field.get("javaName"), "javaType", field.get("javaType"),
                        "nullable", "update".equals(action) ? "true" : field.get("nullable"),
                        "length", field.get("length"), "constraint", ""));
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
            if (name == null || BASE_COLUMNS.contains(name)) {
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
                    "jsonType", jsonType(javaType),
                    "nullable", String.valueOf(!Boolean.FALSE.equals(column.getNullable())),
                    "length", column.getLength() == null ? "" : column.getLength().toString()));
        }
        return List.copyOf(fields);
    }

    private static String jsonType(String javaType) {
        if ("Boolean".equals(javaType)) return "BOOLEAN";
        if (javaType != null && Set.of("Long", "Integer", "java.math.BigDecimal").contains(javaType)) return "NUMBER";
        return "STRING";
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

    private List<CodegenPlanBO.DiagnosticBO> validateDependencies(CodegenConfigBO config, CodegenSchemaBO.TableBO table) {
        Map<String, Object> model = renderModel(config, table, "dependency-check");
        Set<String> artifacts = Set.copyOf(config.getArtifacts());
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        if (artifacts.contains("command")) {
            add(dependencies, "domain-service", "domain-model", "result", "create-converter", "update-converter", "delete-converter", "result-converter");
        }
        if (artifacts.contains("query")) {
            add(dependencies, "domain-service", "domain-model", "domain-query", "result", "result-converter", "page-converter");
        }
        if (artifacts.contains("domain-service")) add(dependencies, "domain-model", "domain-query");
        if (artifacts.contains("domain-impl")) add(dependencies, "domain-service", "domain-model", "domain-query", "repo", "persistence-converter", "po");
        if (artifacts.contains("converter")) {
            add(dependencies, "po", "domain-model", "domain-query", "create-command", "update-command", "delete-command", "page-query", "result");
        }
        if (artifacts.contains("manage")) add(dependencies, "create-command", "update-command", "delete-command", "detail-query", "page-query", "result");
        if (artifacts.contains("manage-impl")) {
            add(dependencies, "manage", "create-command", "update-command", "delete-command", "detail-query", "page-query", "result",
                    "create-executor", "update-executor", "delete-executor", "detail-executor", "page-executor");
        }
        if (artifacts.contains("controller")) add(dependencies, "manage", "create-command", "update-command", "delete-command", "detail-query", "page-query", "result");
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        for (Dependency dependency : dependencies.values()) {
            if (artifacts.contains(dependency.provider())) continue;
            String type = (String) model.get(dependency.typeKey());
            String pkg = (String) model.get(dependency.packageKey());
            String expected = pkg + "." + type;
            String declared = mapping(config, table.getLogicalName() + "." + dependency.key());
            if (declared == null) declared = mapping(config, dependency.key());
            if (declared != null) {
                if ("po".equals(dependency.key()) || declared.equals(expected)) continue;
                diagnostics.add(diagnostic(MISSING_TYPE, "/existingTypeMappings/" + dependency.key(),
                        "declared type must match the generated reference: " + expected));
                continue;
            }
            boolean exists = false;
            try {
                String relative = layout.relativePath(config, dependency.pathKind(), type + ".java");
                new OutputPathValidator().check(java.nio.file.Path.of(config.getOutputRoot()), relative);
                java.nio.file.Path source = java.nio.file.Path.of(config.getOutputRoot()).resolve(relative);
                if (java.nio.file.Files.isRegularFile(source)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(source);
                    new OutputPathValidator().checkJavaSource(relative, bytes);
                    exists = true;
                    if ("domain-service".equals(dependency.key())) {
                        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        CodegenConfigBO.DomainMethodsBO methods = config.getDomainMethods().get(table.getLogicalName());
                        exists = hasDomainMethods(text, methods);
                    }
                }
            } catch (IllegalArgumentException | java.io.IOException | OutputPathValidator.PathValidationException ignored) {
                exists = false;
            }
            if (!exists) diagnostics.add(diagnostic(MISSING_TYPE, "/existingTypeMappings/" + dependency.key(),
                    "missing referenced type " + expected + " for " + table.getLogicalName()));
        }
        return diagnostics;
    }

    private static void add(Map<String, Dependency> target, String... keys) {
        for (String key : keys) target.putIfAbsent(key, dependency(key));
    }

    private static Dependency dependency(String key) {
        return switch (key) {
            case "po" -> new Dependency(key, "po", "po", "poType", "poPackage");
            case "repo" -> new Dependency(key, "repo", "repo", "repoType", "repoPackage");
            case "domain-model" -> new Dependency(key, "domain-model", "domain-model", "domainType", "domainPackage");
            case "domain-query" -> new Dependency(key, "domain-query", "domain-query", "domainQueryType", "domainPackage");
            case "domain-service" -> new Dependency(key, "domain-service", "domain-service", "domainServiceType", "domainServicePackage");
            case "result" -> new Dependency(key, "result", "result", "resultType", "resultPackage");
            case "manage" -> new Dependency(key, "manage", "manage", "manageType", "managePackage");
            case "create-command" -> new Dependency(key, "command", "command", "createCommandType", "commandPackage");
            case "update-command" -> new Dependency(key, "command", "command", "updateCommandType", "commandPackage");
            case "delete-command" -> new Dependency(key, "command", "command", "deleteCommandType", "commandPackage");
            case "detail-query" -> new Dependency(key, "query", "query", "detailQueryType", "queryPackage");
            case "page-query" -> new Dependency(key, "query", "query", "pageQueryType", "queryPackage");
            case "create-executor" -> new Dependency(key, "command", "command-executor", "createExecutorType", "commandExecutorPackage");
            case "update-executor" -> new Dependency(key, "command", "command-executor", "updateExecutorType", "commandExecutorPackage");
            case "delete-executor" -> new Dependency(key, "command", "command-executor", "deleteExecutorType", "commandExecutorPackage");
            case "detail-executor" -> new Dependency(key, "query", "query-executor", "detailExecutorType", "queryExecutorPackage");
            case "page-executor" -> new Dependency(key, "query", "query-executor", "pageExecutorType", "queryExecutorPackage");
            case "persistence-converter" -> new Dependency(key, "converter", "persistence-converter", "persistenceConverterType", "persistenceConverterPackage");
            case "create-converter" -> new Dependency(key, "converter", "application-converter", "createConverterType", "applicationConverterPackage");
            case "update-converter" -> new Dependency(key, "converter", "application-converter", "updateConverterType", "applicationConverterPackage");
            case "delete-converter" -> new Dependency(key, "converter", "application-converter", "deleteConverterType", "applicationConverterPackage");
            case "result-converter" -> new Dependency(key, "converter", "application-converter", "resultConverterType", "applicationConverterPackage");
            case "page-converter" -> new Dependency(key, "converter", "application-converter", "pageConverterType", "applicationConverterPackage");
            default -> throw new IllegalArgumentException("unknown dependency " + key);
        };
    }

    private record Dependency(String key, String provider, String pathKind, String typeKey, String packageKey) {
    }

    public static boolean hasDomainMethods(String source, CodegenConfigBO.DomainMethodsBO methods) {
        if (source == null || methods == null) return false;
        for (String method : java.util.Arrays.asList(methods.getCreate(), methods.getUpdate(), methods.getDelete(), methods.getDetail(), methods.getQuery())) {
            if (blank(method)) return false;
            String declaration = "(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^\\n]*?\\))?\\s+)*(?:public\\s+)?[\\w.<>?, \\t\\[\\]]+\\s+"
                    + java.util.regex.Pattern.quote(method) + "\\s*\\(";
            if (!java.util.regex.Pattern.compile(declaration).matcher(source).find()) return false;
        }
        return true;
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

    private static String empty(String value) {
        return value == null ? "" : value;
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
