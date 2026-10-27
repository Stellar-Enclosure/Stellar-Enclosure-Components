package top.egon.cola.component.codegen.validation;

import jakarta.validation.ConstraintViolation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.model.CodegenProfileEnum;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Annotation-driven and cross-field checks for a generator request.
 *
 * <p>The tool is not a Spring bean. Callers construct it and pass {@link ValidationUtils}.
 * Validation never creates output directories and never adds artifacts that were not requested.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CodegenConfigValidator {

    public static final String CONFIG_REQUIRED = "CONFIG_REQUIRED";

    public static final String UNSUPPORTED_ARTIFACT = "UNSUPPORTED_ARTIFACT";

    public static final String DUPLICATE_PATH = "DUPLICATE_PATH";

    public static final String MISSING_TYPE = "MISSING_TYPE";

    public static final String FIELD_POLICY_REQUIRED = "FIELD_POLICY_REQUIRED";

    public static final String SERVER_FIELD_EXPOSURE = "SERVER_FIELD_EXPOSURE";

    public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";

    private static final Set<String> KNOWN_ARTIFACTS = Set.of(
            "po", "dao", "mapper-xml", "repo", "converter", "command", "query", "result",
            "domain-service", "domain-impl", "domain-model", "domain-query", "manage", "manage-impl", "controller", "event");

    private static final List<String> PERSISTENCE_ARTIFACTS = List.of("po", "dao", "mapper-xml", "repo");

    private static final List<String> BACKEND_CRUD_ARTIFACTS = List.of(
            "po", "dao", "mapper-xml", "repo", "domain-model", "domain-query", "command", "query",
            "result", "converter", "domain-service", "domain-impl", "manage", "manage-impl");

    private static final Set<String> UPPER_ARTIFACTS = Set.of(
            "converter", "command", "query", "result", "domain-service", "domain-impl",
            "domain-model", "domain-query", "manage", "manage-impl", "controller");

    private static final Set<String> SERVER_FIELDS = Set.of(
            "tenant_id", "tenantId", "created_at", "createdAt", "created_by", "createdBy",
            "updated_at", "updatedAt", "updated_by", "updatedBy", "deleted_at", "deletedAt");

    @Qualifier("validationUtils")
    private final ValidationUtils validationUtils;

    public List<CodegenPlanBO.DiagnosticBO> validate(CodegenConfigBO config) {
        if (config == null) {
            return List.of(diagnostic(CONFIG_REQUIRED, "", "config is required"));
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        for (ConstraintViolation<CodegenConfigBO> violation : validationUtils.violations(
                config, CodegenGroups.Plan.class)) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer(violation.getPropertyPath().toString()),
                    violation.getMessage()));
        }
        if (config.getConfigVersion() != null && config.getConfigVersion() != 1) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/configVersion", "configVersion must be 1"));
        }
        diagnostics.addAll(crossChecks(config));
        if (!diagnostics.isEmpty()) {
            log.warn("codegen config rejected: {}", diagnostics.stream()
                    .map(CodegenPlanBO.DiagnosticBO::getCode)
                    .distinct()
                    .toList());
        }
        return List.copyOf(diagnostics);
    }

    public List<CodegenPlanBO.DiagnosticBO> validateApply(CodegenPlanBO plan) {
        if (plan == null) {
            return List.of(diagnostic(CONFIG_REQUIRED, "", "plan is required"));
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        for (ConstraintViolation<CodegenPlanBO> violation : validationUtils.violations(
                plan, CodegenGroups.Apply.class)) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer(violation.getPropertyPath().toString()),
                    violation.getMessage()));
        }
        if (!plan.supportedFormat()) {
            diagnostics.add(diagnostic(UNSUPPORTED_FORMAT, "/formatVersion", "formatVersion must be 2"));
        }
        if (!diagnostics.isEmpty()) {
            log.warn("codegen apply rejected: {}", diagnostics.stream()
                    .map(CodegenPlanBO.DiagnosticBO::getCode)
                    .distinct()
                    .toList());
        }
        return List.copyOf(diagnostics);
    }

    /**
     * Returns a detached config with aliases and preset tokens normalized.
     * Referenced PO/DAO types are not turned into extra artifacts.
     *
     * @param config original request
     * @return normalized copy
     */
    public CodegenConfigBO normalize(CodegenConfigBO config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        CodegenConfigBO copy = config.snapshot();
        copy.setArtifacts(new ArrayList<>(expand(config)));
        return copy;
    }

    private List<CodegenPlanBO.DiagnosticBO> crossChecks(CodegenConfigBO config) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (!blank(config.getBasePackage()) && !SourceVersion.isName(config.getBasePackage())) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/basePackage", "base package must be a Java package name"));
        }
        if (!blank(config.getDomain()) && (!SourceVersion.isIdentifier(config.getDomain()) || SourceVersion.isKeyword(config.getDomain()))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/domain", "domain must be one Java package segment"));
        }
        List<String> expanded = expand(config);
        Set<String> seen = new LinkedHashSet<>();
        for (String artifact : expanded) {
            if (!seen.add(artifact)) {
                diagnostics.add(diagnostic(DUPLICATE_PATH, "/artifacts", "duplicate artifact " + artifact));
            }
        }
        if (config.getProfile() == CodegenProfileEnum.SERVICE && seen.contains("controller")) {
            diagnostics.add(diagnostic(UNSUPPORTED_ARTIFACT, "/artifacts",
                    "service profile does not generate controller"));
        }
        diagnostics.addAll(checkEvents(config, seen));
        diagnostics.addAll(checkInput(config, seen));
        diagnostics.addAll(checkRoots(config, seen));
        diagnostics.addAll(checkTables(config.getTables()));
        diagnostics.addAll(checkExistingTypes(seen, config.getExistingTypes()));
        diagnostics.addAll(checkFieldPolicies(config, seen));
        diagnostics.addAll(checkDomainMethods(config, seen));
        diagnostics.addAll(checkApiContract(config, seen));
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkEvents(CodegenConfigBO config, Set<String> artifacts) {
        CodegenConfigBO.EventsBO events = config.getEvents();
        if (events == null) {
            return artifacts.contains("event") ? List.of(diagnostic(CONFIG_REQUIRED, "/events", "event artifact requires definitions")) : List.of();
        }
        List<CodegenConfigBO.EventDefinitionBO> definitions = events.getDefinitions();
        if (!events.isEnabled()) {
            return (definitions == null || definitions.isEmpty()) ? List.of()
                    : List.of(diagnostic(CONFIG_REQUIRED, "/events", "event definitions require events.enabled=true"));
        }
        if (definitions == null || definitions.isEmpty()) {
            return List.of(diagnostic(CONFIG_REQUIRED, "/events/definitions",
                    "enabled events require explicit definitions"));
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (!artifacts.contains("command") && !artifacts.contains("event")) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/artifacts", "event generation requires the event or command artifact"));
        }
        if (config.getProfile() != CodegenProfileEnum.LIGHT
                && (config.getRoots() == null || blank(config.getRoots().get("adapter")))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/modulePaths/adapter", "event generation requires an adapter module path"));
        }
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < definitions.size(); index++) {
            CodegenConfigBO.EventDefinitionBO event = definitions.get(index);
            String pointer = "/events/definitions/" + index;
            if (event == null) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer, "event definition is required"));
                continue;
            }
            boolean delegate = "delegate".equals(event.getAction());
            if ((!delegate && blank(event.getTable())) || (!blank(event.getTable())
                    && (config.getTables() == null || !config.getTables().contains(event.getTable())))) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/table", "event table must be selected"));
            }
            if (blank(event.getName()) || !event.getName().matches("[A-Z][A-Za-z0-9_]*Event")
                    || !SourceVersion.isIdentifier(event.getName()) || SourceVersion.isKeyword(event.getName())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/name", "event name must be a Java type ending in Event"));
            } else if (!names.add(event.getName().toLowerCase(java.util.Locale.ROOT))) {
                diagnostics.add(diagnostic(DUPLICATE_PATH, pointer + "/name", "event name must be unique"));
            }
            if (blank(event.getAction()) || !Set.of("create", "update", "delete", "delegate").contains(event.getAction())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/action", "event action must be create, update, delete or delegate"));
            } else if (!delegate && !artifacts.contains("command")) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/artifacts", "CRUD event generation requires command executors"));
            }
            if (blank(event.getSchemaVersion()) || !event.getSchemaVersion().matches("[A-Za-z0-9._-]{1,32}")) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/schemaVersion", "an explicit event schema version is required"));
            }
            if (blank(event.getIdempotencyType()) || !event.getIdempotencyType().contains(".")
                    || !SourceVersion.isName(event.getIdempotencyType())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/idempotencyType", "an existing idempotency type is required"));
            } else if (!blank(config.getBasePackage()) && List.of("infrastructure", "adapter", "starter", "start").stream()
                    .anyMatch(layer -> event.getIdempotencyType().startsWith(config.getBasePackage() + "." + layer + "."))) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/idempotencyType",
                        "idempotency must use an application/domain port or shared contract, not an outer-layer implementation"));
            }
            if (blank(event.getIdempotencyBean()) || !event.getIdempotencyBean().matches("[A-Za-z0-9_.-]+")) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/idempotencyBean", "an existing idempotency bean name is required"));
            }
            if (blank(event.getIdempotencyMethod()) || !SourceVersion.isIdentifier(event.getIdempotencyMethod())
                    || SourceVersion.isKeyword(event.getIdempotencyMethod())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/idempotencyMethod", "an idempotency method name is required"));
            }
            if (!delegate && event.getFieldMappings() == null) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fieldMappings", "explicit field mappings are required"));
            }
            if (delegate) {
                if (event.getFieldMappings() != null && !event.getFieldMappings().isEmpty()) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fieldMappings", "delegate events do not map a generated command"));
                }
                if (!validHandlerType(config, event.getHandlerType()) || !validBean(event.getHandlerBean())
                        || !validMethod(event.getHandlerMethod())) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/handlerType", "delegate event requires an existing application-layer handler type, bean and method"));
                } else if (!blank(event.getName()) && !blank(event.getIdempotencyType())) {
                    String handler = event.getHandlerType().substring(event.getHandlerType().lastIndexOf('.') + 1);
                    String guard = event.getIdempotencyType().substring(event.getIdempotencyType().lastIndexOf('.') + 1);
                    Set<String> reserved = Set.of(event.getName(), event.getName() + "Exe", "Valid", "NotNull",
                            "Qualifier", "Service", "Validated", "Transactional", "RequiredArgsConstructor", "Autowired", "Slf4j");
                    if (reserved.contains(handler) || reserved.contains(guard)
                            || (handler.equals(guard) && !event.getHandlerType().equals(event.getIdempotencyType()))) {
                        diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/handlerType", "handler or idempotency type conflicts with an event executor import"));
                    }
                }
            }
            Set<String> fieldNames = new LinkedHashSet<>();
            if (event.getFieldMappings() != null) fieldNames.addAll(event.getFieldMappings().values());
            if (event.getFields() != null) {
                for (CodegenConfigBO.EventFieldBO field : event.getFields()) {
                    if (field == null || blank(field.getName()) || !field.getName().matches("[a-z][A-Za-z0-9]*")
                            || SourceVersion.isKeyword(field.getName()) || Set.of("eventId", "schemaVersion").contains(field.getName())
                            || !fieldNames.add(field.getName()) || !eventFieldType(config, field.getJavaType(), 0)
                            || (field.getMaxLength() != null && (field.getMaxLength() < 1 || field.getMaxLength() > 100000
                            || !"String".equals(field.getJavaType())))) {
                        diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fields", "event fields require unique safe names, supported types and valid string lengths"));
                    }
                }
            }
        }
        return diagnostics;
    }

    private static List<String> expand(CodegenConfigBO config) {
        List<String> expanded = new ArrayList<>();
        List<String> artifacts = config.getArtifacts();
        if (artifacts == null) {
            return expanded;
        }
        for (String artifact : artifacts) {
            if (artifact == null || artifact.isBlank()) {
                expanded.add("");
                continue;
            }
            String token = alias(artifact.trim());
            if ("persistence-crud".equals(token)) {
                expanded.addAll(PERSISTENCE_ARTIFACTS);
            } else if ("backend-crud".equals(token)) {
                expanded.addAll(BACKEND_CRUD_ARTIFACTS);
                if (config.getProfile() != CodegenProfileEnum.SERVICE) {
                    expanded.add("controller");
                }
            } else {
                expanded.add(token);
            }
        }
        return expanded;
    }

    private static String alias(String artifact) {
        return switch (artifact) {
            case "pojo" -> "po";
            case "mapper" -> "mapper-xml";
            default -> artifact;
        };
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkInput(CodegenConfigBO config, Set<String> artifacts) {
        CodegenConfigBO.InputBO input = config.getInput();
        if (input == null || input.getMode() == null || input.getMode().isBlank()) {
            return List.of();
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if ("event".equals(input.getMode())) {
            boolean invalidEvent = config.getEvents() != null && config.getEvents().getDefinitions() != null
                    && config.getEvents().getDefinitions().stream().anyMatch(event -> event == null
                    || !"delegate".equals(event.getAction()) || !blank(event.getTable()));
            if (!artifacts.equals(Set.of("event")) || (config.getTables() != null && !config.getTables().isEmpty())
                    || (input.getSchemaFiles() != null && !input.getSchemaFiles().isEmpty())
                    || !blank(input.getResourceRoot()) || !blank(input.getManifest()) || invalidEvent) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input", "event mode requires only table-independent delegate events and no DDL input"));
            }
            return diagnostics;
        }
        if ("schema".equals(input.getMode())) {
            if (input.getSchemaFiles() == null || input.getSchemaFiles().isEmpty()) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input/schemaFiles",
                        "schema mode requires schemaFiles"));
            }
            if (!blank(input.getManifest())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input/manifest",
                        "schema mode does not accept a manifest"));
            }
            return diagnostics;
        }
        if ("manifest".equals(input.getMode())) {
            if (blank(input.getManifest())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input/manifest",
                        "manifest mode requires a manifest"));
            }
            if (blank(input.getResourceRoot())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input/resourceRoot",
                        "manifest mode requires resourceRoot"));
            }
            if (input.getSchemaFiles() != null && !input.getSchemaFiles().isEmpty()) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/input/schemaFiles",
                        "manifest mode does not accept schemaFiles"));
            }
            return diagnostics;
        }
        return List.of(diagnostic(CONFIG_REQUIRED, "/input/mode", "input mode must be schema, manifest or event"));
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkRoots(CodegenConfigBO config, Set<String> artifacts) {
        if (config.getProfile() != CodegenProfileEnum.WEB && config.getProfile() != CodegenProfileEnum.SERVICE) {
            return List.of();
        }
        Map<String, String> roots = config.getRoots();
        if (roots == null || roots.isEmpty()) {
            return List.of(diagnostic(CONFIG_REQUIRED, "/modulePaths", "web and service require modulePaths"));
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : roots.entrySet()) {
            if (blank(entry.getKey()) || blank(entry.getValue())) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/modulePaths", "modulePaths entries must be non-blank"));
            } else {
                try {
                    java.nio.file.Path path = java.nio.file.Path.of(entry.getValue());
                    String normalized = path.normalize().toString();
                    if (path.isAbsolute() || entry.getValue().contains("\\") || entry.getValue().contains("..")
                            || normalized.isBlank() || ".".equals(normalized)) {
                        diagnostics.add(diagnostic(CONFIG_REQUIRED, "/modulePaths", "module paths must stay below the output root"));
                    } else if (!values.add(normalized.toLowerCase(java.util.Locale.ROOT))) {
                        diagnostics.add(diagnostic(DUPLICATE_PATH, "/modulePaths", "duplicate module path " + entry.getValue()));
                    }
                } catch (java.nio.file.InvalidPathException failure) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/modulePaths", "invalid module path"));
                }
            }
        }
        Set<String> required = new LinkedHashSet<>();
        if (!java.util.Collections.disjoint(artifacts, Set.of("domain-model", "domain-query", "domain-service"))) required.add("domain");
        if (!java.util.Collections.disjoint(artifacts, Set.of("po", "dao", "mapper-xml", "repo", "domain-impl", "converter"))) required.add("infrastructure");
        if (!java.util.Collections.disjoint(artifacts, Set.of("command", "query", "result", "manage", "manage-impl", "converter", "event"))) required.add("application");
        if (artifacts.contains("controller") || (config.getEvents() != null && config.getEvents().isEnabled())) {
            required.add("adapter");
            if (config.getEvents() != null && config.getEvents().isEnabled()) required.add("application");
        }
        for (String module : required) {
            if (blank(roots.get(module))) diagnostics.add(diagnostic(CONFIG_REQUIRED, "/modulePaths/" + module, "selected output requires this module path"));
        }
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkTables(List<String> tables) {
        if (tables == null) {
            return List.of();
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String table : tables) {
            if (blank(table) || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/logicalTables", "logical table must be a nonblank identifier"));
            } else if (!seen.add(table)) {
                diagnostics.add(diagnostic(DUPLICATE_PATH, "/logicalTables", "duplicate logical table " + table));
            }
        }
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkExistingTypes(
            Set<String> artifacts, Map<String, String> existingTypes) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (existingTypes != null) {
            for (Map.Entry<String, String> entry : existingTypes.entrySet()) {
                if (blank(entry.getKey()) || !entry.getKey().matches("[A-Za-z0-9_.-]+")
                        || entry.getKey().contains("..") || !qualifiedType(entry.getValue())) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/existingTypeMappings", "existing type mappings require safe keys and qualified Java types"));
                }
            }
        }
        if (needsType(artifacts, "po", "dao", "mapper-xml", "repo") && !artifacts.contains("po")
                && blank(mapping(existingTypes, "po"))) {
            diagnostics.add(missingType("po"));
        }
        if (needsType(artifacts, "dao", "mapper-xml", "repo") && !artifacts.contains("dao")
                && blank(mapping(existingTypes, "dao"))) {
            diagnostics.add(missingType("dao"));
        }
        for (String artifact : artifacts) {
            if (!artifact.isEmpty() && !KNOWN_ARTIFACTS.contains(artifact)) {
                diagnostics.add(diagnostic(UNSUPPORTED_ARTIFACT, "/artifacts", "unsupported artifact " + artifact));
            }
        }
        return diagnostics;
    }

    private static boolean needsType(Set<String> artifacts, String... triggers) {
        for (String trigger : triggers) {
            if (artifacts.contains(trigger)) {
                return true;
            }
        }
        return false;
    }

    private static CodegenPlanBO.DiagnosticBO missingType(String typeName) {
        CodegenPlanBO.DiagnosticBO diagnostic = diagnostic(MISSING_TYPE, "/existingTypeMappings/" + typeName,
                "missing existing type " + typeName);
        diagnostic.setArtifact(typeName);
        return diagnostic;
    }

    private static String mapping(Map<String, String> existingTypes, String key) {
        if (existingTypes == null) {
            return null;
        }
        return existingTypes.get(key);
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkFieldPolicies(
            CodegenConfigBO config, Set<String> artifacts) {
        boolean upper = false;
        for (String artifact : artifacts) {
            if (UPPER_ARTIFACTS.contains(artifact)) {
                upper = true;
                break;
            }
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (config.getTableFieldPolicies() != null) {
            for (String table : config.getTableFieldPolicies().keySet()) {
                if (config.getTables() == null || !config.getTables().contains(table)) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/tableFieldPolicies/" + table, "table policy must target a selected table"));
                }
            }
        }
        if (config.getTables() == null || config.getTables().isEmpty()) {
            return diagnostics;
        }
        for (String table : config.getTables()) {
            if (blank(table)) continue;
            CodegenConfigBO.FieldPoliciesBO policies = policies(config, table);
            if (upper && (policies == null || policies.getCreate() == null || policies.getUpdate() == null
                    || policies.getResult() == null || policies.getFilter() == null || policies.getSort() == null)) {
                diagnostics.add(diagnostic(FIELD_POLICY_REQUIRED, "/fieldPolicies",
                        "upper CRUD requires explicit create, update, result, filter and sort policies for " + table));
            } else if (policies != null) {
                diagnostics.addAll(serverFieldDiagnostics(policies));
            }
        }
        return diagnostics;
    }

    public static CodegenConfigBO.FieldPoliciesBO policies(CodegenConfigBO config, String table) {
        return config.getTableFieldPolicies() != null && config.getTableFieldPolicies().containsKey(table)
                ? config.getTableFieldPolicies().get(table) : config.getFieldPolicies();
    }

    private static List<CodegenPlanBO.DiagnosticBO> serverFieldDiagnostics(CodegenConfigBO.FieldPoliciesBO policies) {
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        rejectServerFields(diagnostics, "/fieldPolicies/create", policies.getCreate());
        rejectServerFields(diagnostics, "/fieldPolicies/update", policies.getUpdate());
        rejectServerFields(diagnostics, "/fieldPolicies/result", policies.getResult());
        return diagnostics;
    }

    private static void rejectServerFields(
            List<CodegenPlanBO.DiagnosticBO> diagnostics, String pointer, List<String> fields) {
        if (fields == null) {
            return;
        }
        for (String field : fields) {
            if (field != null && SERVER_FIELDS.contains(field)) {
                diagnostics.add(diagnostic(SERVER_FIELD_EXPOSURE, pointer,
                        "server-managed field cannot be exposed: " + field));
            }
        }
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkDomainMethods(CodegenConfigBO config, Set<String> artifacts) {
        if (java.util.Collections.disjoint(artifacts, Set.of("command", "query", "domain-service", "domain-impl"))) {
            return List.of();
        }
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (config.getTables() == null) return diagnostics;
        if (config.getDomainMethods() != null) {
            for (String table : config.getDomainMethods().keySet()) {
                if (!config.getTables().contains(table)) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/domainMethods/" + table, "domain methods must target a selected table"));
                }
            }
        }
        for (String table : config.getTables()) {
            if (blank(table)) continue;
            CodegenConfigBO.DomainMethodsBO methods = config.getDomainMethods() == null ? null : config.getDomainMethods().get(table);
            if (methods == null) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, "/domainMethods/" + table, "explicit business domain method names are required"));
                continue;
            }
            List<String> names = java.util.Arrays.asList(methods.getCreate(), methods.getUpdate(), methods.getDelete(),
                    methods.getDetail(), methods.getQuery());
            Set<String> unique = new LinkedHashSet<>();
            for (String name : names) {
                if (!validMethod(name) || Set.of("create", "update", "delete", "detail", "query", "save", "find", "list").contains(name)
                        || !unique.add(name)) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/domainMethods/" + table,
                            "domain methods must be distinct business-named Java methods, not generic CRUD names"));
                    break;
                }
            }
        }
        return diagnostics;
    }

    private static List<CodegenPlanBO.DiagnosticBO> checkApiContract(CodegenConfigBO config, Set<String> artifacts) {
        CodegenConfigBO.ApiContractBO api = config.getApiContract();
        List<CodegenPlanBO.DiagnosticBO> diagnostics = new ArrayList<>();
        if (artifacts.contains("domain-impl") && (api == null || !qualifiedType(api.getExistingErrorMapper()))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/existingErrorMapper", "domain implementation requires an existing error mapper type"));
        } else if (artifacts.contains("domain-impl") && !blank(config.getBasePackage())
                && List.of("application", "adapter", "starter", "start").stream()
                .anyMatch(layer -> api.getExistingErrorMapper().startsWith(config.getBasePackage() + "." + layer + "."))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/existingErrorMapper", "infrastructure must not depend on an outer application or adapter error type"));
        }
        if (!artifacts.contains("controller") || config.getProfile() == null || !config.getProfile().allowsController()) {
            return diagnostics;
        }
        Set<String> paths = new LinkedHashSet<>();
        Set<String> resourceCodes = new LinkedHashSet<>();
        if (config.getTables() == null) return diagnostics;
        if (api == null || blank(api.getTransportMode()) || !Set.of("generate", "existing").contains(api.getTransportMode())) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/transportMode", "select generated or existing permission-aware MVC transport"));
        } else if ("existing".equals(api.getTransportMode())
                && (!qualifiedType(api.getTransportConfigType()) || !validBean(api.getTransportBean()))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/transportConfigType", "existing MVC transport requires its configuration type and bean name"));
        } else if ("generate".equals(api.getTransportMode())
                && (!blank(api.getTransportConfigType()) || !blank(api.getTransportBean()))) {
            diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/transportConfigType", "generated transport does not accept an existing configuration type or bean"));
        }
        Set<String> declaredPermissions = new LinkedHashSet<>();
        if (api != null) {
            for (List<String> group : List.of(api.getPermissionReferences() == null ? List.<String>of() : api.getPermissionReferences(),
                    api.getLocalPermissions() == null ? List.<String>of() : api.getLocalPermissions())) {
                for (String permission : group) {
                    if (!safeCode(permission) || !declaredPermissions.add(permission)) {
                        diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/permissionReferences", "permissions must be valid, unique and either local or external"));
                    }
                }
            }
        }
        if (api != null && api.getTables() != null) {
            for (String table : api.getTables().keySet()) {
                if (!config.getTables().contains(table)) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, "/apiContract/tables/" + table, "API contract must target a selected table"));
                }
            }
        }
        for (String table : config.getTables()) {
            if (blank(table)) continue;
            CodegenConfigBO.ApiTableBO entry = api == null || api.getTables() == null ? null : api.getTables().get(table);
            String pointer = "/apiContract/tables/" + table;
            if (entry == null) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer, "explicit HTTP resource, operation and field policies are required"));
                continue;
            }
            String basePath = blank(entry.getBasePath()) && config.getTables().size() == 1 && api != null
                    ? api.getBasePath() : entry.getBasePath();
            String prefix = config.getProfile() == CodegenProfileEnum.WEB ? "/api/v1/" : "/api/";
            if (blank(basePath) || !basePath.startsWith(prefix) || !basePath.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
                    || !paths.add(basePath.toLowerCase(java.util.Locale.ROOT))) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/basePath", "a unique native API path under " + prefix + " is required"));
            }
            if (entry.getOperations() == null || !entry.getOperations().keySet().equals(Set.of("create", "update", "delete", "detail", "page"))) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/operations", "exactly the five generated API operations must be configured"));
            }
            for (String operation : List.of("create", "update", "delete", "detail", "page")) {
                CodegenConfigBO.ApiOperationBO item = entry.getOperations() == null ? null : entry.getOperations().get(operation);
                if (item == null || !safeCode(item.getScope()) || !safeCode(item.getPermission())
                        || !safeCode(item.getResourceCode())
                        || !safeI18n(item.getLabelPage()) || !safeI18n(item.getLabelKey())
                        || !declaredPermissions.contains(item.getPermission())
                        || !resourceCodes.add(item.getResourceCode())) {
                    diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/operations/" + operation,
                            "unique resource, declared permission, scope and localization keys are required"));
                }
            }
            if (entry.getFields() == null) {
                diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fields", "explicit field resources are required"));
            } else {
                for (Map.Entry<String, CodegenConfigBO.ApiFieldBO> field : entry.getFields().entrySet()) {
                    CodegenConfigBO.ApiFieldBO item = field.getValue();
                    if (blank(field.getKey()) || item == null || !safeI18n(item.getLabelPage()) || !safeI18n(item.getLabelKey())) {
                        diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fields/" + field.getKey(), "field resource metadata is incomplete"));
                        continue;
                    }
                    for (String key : java.util.Arrays.asList(item.getResultKey(), item.getCreateKey(), item.getUpdateKey())) {
                        if (key != null && (!safeCode(key) || !resourceCodes.add(key))) {
                            diagnostics.add(diagnostic(CONFIG_REQUIRED, pointer + "/fields/" + field.getKey(), "field resource keys must be unique and valid"));
                        }
                    }
                }
            }
        }
        return diagnostics;
    }

    private static boolean validHandlerType(CodegenConfigBO config, String type) {
        return qualifiedType(type) && !blank(config.getBasePackage()) && type.startsWith(config.getBasePackage() + ".application.");
    }

    private static boolean qualifiedType(String value) {
        return !blank(value) && value.contains(".") && SourceVersion.isName(value);
    }

    private static boolean validBean(String value) {
        return !blank(value) && value.matches("[A-Za-z0-9_.-]+");
    }

    private static boolean validMethod(String value) {
        return !blank(value) && value.matches("[a-z][A-Za-z0-9]*")
                && SourceVersion.isIdentifier(value) && !SourceVersion.isKeyword(value);
    }

    private static boolean safeCode(String value) {
        return !blank(value) && value.length() <= 128 && value.matches("[A-Za-z][A-Za-z0-9._:-]*");
    }

    private static boolean safeI18n(String value) {
        return !blank(value) && value.length() <= 128 && value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                && java.util.Arrays.stream(value.split("\\.")).noneMatch(Set.of("__proto__", "constructor", "prototype")::contains);
    }

    public static boolean scalarEventType(String value) {
        return value != null && Set.of("String", "Long", "Integer", "Boolean", "java.math.BigDecimal", "java.util.UUID",
                "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime").contains(value);
    }

    private static boolean eventFieldType(CodegenConfigBO config, String value, int depth) {
        if (blank(value) || value.length() > 256 || depth > 3) return false;
        if (scalarEventType(value)) return true;
        for (String prefix : List.of("java.util.List<", "java.util.Set<", "java.util.Map<String,")) {
            if (value.startsWith(prefix) && value.endsWith(">")) {
                return eventFieldType(config, value.substring(prefix.length(), value.length() - 1), depth + 1);
            }
        }
        if (value.endsWith("[]")) return eventFieldType(config, value.substring(0, value.length() - 2), depth + 1);
        if (!qualifiedType(value) || value.startsWith("java.")) return false;
        return blank(config.getBasePackage()) || List.of("infrastructure", "adapter", "starter", "start").stream()
                .noneMatch(layer -> value.startsWith(config.getBasePackage() + "." + layer + "."));
    }

    private static CodegenPlanBO.DiagnosticBO diagnostic(String code, String pointer, String message) {
        return CodegenPlanBO.DiagnosticBO.of(code, pointer, message);
    }

    private static String pointer(String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return "";
        }
        return "/" + propertyPath.replace(".", "/").replace("[", "/").replace("]", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
