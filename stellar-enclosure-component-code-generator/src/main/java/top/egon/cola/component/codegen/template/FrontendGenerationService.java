package top.egon.cola.component.codegen.template;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.model.CodegenSchemaBO;
import top.egon.cola.component.codegen.update.GenerationPlanService;
import top.egon.cola.component.codegen.update.GenerationStateRepository;
import top.egon.cola.component.codegen.validation.GenerationScopeValidator;
import top.egon.cola.component.codegen.validation.OutputPathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders one React feature per selected table using the existing backend HTTP contract. */
public final class FrontendGenerationService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String REGISTRY = "src/generated/permissions.ts";
    private final FreeMarkerTemplateService templates = new FreeMarkerTemplateService("frontend");
    private final GenerationScopeValidator scope = new GenerationScopeValidator();
    private final GenerationStateRepository states = new GenerationStateRepository();
    private final OutputPathValidator paths = new OutputPathValidator();

    public String templateDigest() {
        return templates.getTemplateSetDigest();
    }

    public List<GenerationPlanService.RenderedFile> render(CodegenConfigBO config, CodegenSchemaBO schema, Path root) {
        requireScaffold(root);
        if (config.getProfile() == null || !config.getProfile().allowsController()
                || config.getArtifacts() == null || !config.getArtifacts().contains("controller")) {
            throw invalid("React generation requires a Light or Web HTTP controller contract");
        }
        CodegenConfigBO.FrontendBO frontend = config.getFrontend();
        if (frontend == null || frontend.getTables() == null || config.getTables() == null
                || !frontend.getTables().keySet().equals(new LinkedHashSet<>(config.getTables()))) {
            throw invalid("frontend.tables must describe exactly the selected logical tables");
        }
        if (frontend.getResponseMode() != null && !Set.of("direct", "result-record").contains(frontend.getResponseMode())) {
            throw invalid("frontend.responseMode must be direct or result-record");
        }
        Map<String, ModuleMetadata> modules = previousModules(root, new LinkedHashSet<>(config.getTables()));
        List<GenerationPlanService.RenderedFile> files = new ArrayList<>();
        for (CodegenSchemaBO.TableBO table : schema.getTables()) {
            ModuleMetadata module = renderTable(config, table, frontend.getTables().get(table.getLogicalName()), files);
            if (modules.putIfAbsent(module.table(), module) != null) throw invalid("duplicate frontend table");
        }
        List<ModuleMetadata> ordered = modules.values().stream().sorted(Comparator.comparing(ModuleMetadata::feature)).toList();
        validateModules(ordered);
        Map<String, Object> registry = registryModel(ordered);
        files.add(new GenerationPlanService.RenderedFile(REGISTRY, "frontend-registry", null, templates.render("registry", registry)));
        paths.checkDistinct(files.stream().map(GenerationPlanService.RenderedFile::relativePath).toList());
        return List.copyOf(files);
    }

    private void requireScaffold(Path root) {
        if (!Files.isDirectory(root)) throw invalid("frontend.outputRoot must be an existing React project");
        paths.check(root, "permission-manifest.config.json");
        paths.check(root, REGISTRY);
        try {
            JsonNode manifest = MAPPER.readTree(root.resolve("permission-manifest.config.json").toFile());
            boolean prefix = false;
            for (JsonNode value : manifest.path("ownedKeyPrefixes")) if ("app.".equals(value.asText())) prefix = true;
            boolean entry = false;
            for (JsonNode module : manifest.path("modules")) {
                for (JsonNode value : module.path("entryPoints")) if (REGISTRY.equals(value.asText())) entry = true;
            }
            if (!prefix || !entry || !Files.isRegularFile(root.resolve(REGISTRY))) {
                throw invalid("React scaffold must own app. resources and scan the generated permissions registry");
            }
        } catch (IOException failure) {
            throw invalid("React scaffold permission configuration is missing or invalid");
        }
    }

    private Map<String, ModuleMetadata> previousModules(Path root, Set<String> selected) {
        Map<String, ModuleMetadata> modules = new LinkedHashMap<>();
        for (CodegenPlanBO.ArtifactStateBO artifact : states.load(root).getArtifacts()) {
            if (!"frontend-metadata".equals(artifact.getArtifact()) || selected.contains(artifact.getLogicalTable())) continue;
            String path = artifact.getPath();
            paths.check(root, path);
            try {
                byte[] content = Files.readAllBytes(root.resolve(path));
                if (!artifact.isPreviousGeneratedHashPresent()
                        || !GenerationPlanService.sha256(content).equals(artifact.getPreviousGeneratedHash())) {
                    throw invalid("previous frontend metadata was changed: " + path);
                }
                ModuleMetadata value = MAPPER.readValue(content, ModuleMetadata.class);
                if (!path.equals("src/features/" + value.feature() + "/" + value.feature() + ".metadata.json")
                        || !value.table().equals(artifact.getLogicalTable()) || modules.putIfAbsent(value.table(), value) != null) {
                    throw invalid("previous frontend metadata is inconsistent: " + path);
                }
            } catch (IOException failure) {
                throw invalid("previous frontend metadata is unavailable: " + path);
            }
        }
        return modules;
    }

    @SuppressWarnings("unchecked")
    private ModuleMetadata renderTable(CodegenConfigBO config, CodegenSchemaBO.TableBO table,
                                       CodegenConfigBO.FrontendTableBO frontend,
                                       List<GenerationPlanService.RenderedFile> files) {
        String tableName = table.getLogicalName();
        if (tableName == null || !tableName.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")) {
            throw invalid("React feature tables must use lowercase snake_case: " + tableName);
        }
        if (frontend == null || !validText(frontend.getTitle()) || frontend.getRoute() == null
                || !frontend.getRoute().matches("/[a-z][a-z0-9-]*(?:/[a-z][a-z0-9-]*)*")
                || "/home".equals(frontend.getRoute()) || frontend.getFields() == null) {
            throw invalid("frontend table title, route and field metadata are required: " + tableName);
        }
        String feature = tableName.replace('_', '-');
        Map<String, Object> model = new LinkedHashMap<>(scope.renderModel(config, table, "controller"));
        String stem = (String) model.get("stem");
        Map<String, Map<String, String>> apiFields = (Map<String, Map<String, String>>) model.get("apiFields");
        Map<String, Map<String, String>> operations = (Map<String, Map<String, String>>) model.get("apiOperations");
        Map<String, Map<String, Object>> enriched = new LinkedHashMap<>();
        for (String kind : List.of("createFields", "updateFields", "resultFields", "filterFields")) {
            for (Map<String, String> field : (List<Map<String, String>>) model.get(kind)) {
                enriched.computeIfAbsent(field.get("column"), key -> enrichField(field, frontend.getFields().get(key), apiFields));
            }
        }
        if (!frontend.getFields().keySet().equals(enriched.keySet())) {
            throw invalid("frontend.fields must describe exactly the selected UI fields: " + tableName);
        }
        int index = 0;
        for (Map<String, Object> field : enriched.values()) field.put("index", index++);
        for (String kind : List.of("createFields", "updateFields", "resultFields", "filterFields")) {
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, String> field : (List<Map<String, String>>) model.get(kind)) selected.add(enriched.get(field.get("column")));
            model.put(kind, selected);
        }
        List<Map<String, Object>> create = (List<Map<String, Object>>) model.get("createFields");
        List<Map<String, Object>> update = (List<Map<String, Object>>) model.get("updateFields");
        for (Map<String, Object> field : enriched.values()) {
            boolean inCreate = create.contains(field), inUpdate = update.contains(field);
            field.put("createPresent", inCreate);
            field.put("updatePresent", inUpdate);
            field.put("requiredCreate", inCreate && "false".equals(field.get("nullable")));
        }
        model.put("formFields", enriched.values().stream().filter(value -> create.contains(value) || update.contains(value)).toList());
        model.put("hasDecimal", enriched.values().stream().anyMatch(value -> "decimal".equals(value.get("control"))));
        model.put("hasFormDecimal", enriched.values().stream().anyMatch(value -> "decimal".equals(value.get("control")) && (create.contains(value) || update.contains(value))));
        model.put("hasResultDecimal", ((List<Map<String, Object>>) model.get("resultFields")).stream().anyMatch(value -> "decimal".equals(value.get("control"))));
        model.put("hasFilterDecimal", ((List<Map<String, Object>>) model.get("filterFields")).stream().anyMatch(value -> "decimal".equals(value.get("control"))));
        model.put("feature", feature);
        model.put("featureJson", json(feature));
        model.put("featureIdentifier", "generated" + stem);
        model.put("titleJson", json(frontend.getTitle()));
        model.put("routeJson", json(frontend.getRoute()));
        model.put("apiBasePathJson", json(model.get("apiBasePath")));
        String responseMode = config.getFrontend().getResponseMode();
        model.put("responseMode", responseMode == null ? (config.getProfile().code().equals("light") ? "result-record" : "direct") : responseMode);
        model.put("pageKeyJson", json("app.page." + feature));
        model.put("pageCodeJson", json("app." + feature));
        model.put("componentJson", json(stem + "Page"));
        model.put("pagePermissionsJson", json(model.get("pagePermissions")));
        model.put("idResultKeyJson", fieldKey(apiFields, "id", "resultKey"));
        model.put("expectedVersionKeyJson", fieldKey(apiFields, "expectedVersion", "updateKey"));
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, String> labels = Map.of("detail", "详情", "create", "新增", "update", "编辑", "delete", "删除");
        for (String action : List.of("detail", "create", "update", "delete")) {
            String key = "app.button." + feature + "." + action;
            model.put(action + "KeyJson", json(key));
            actions.add(Map.of("keyJson", json(key), "nameJson", json(action),
                    "permissionJson", json(operations.get(action).get("permission")),
                    "labelKeyJson", json("button." + action), "labelJson", json(labels.get(action))));
        }
        model.put("actions", actions);
        String directory = "src/features/" + feature + "/";
        List<String> selectedTemplates = new ArrayList<>(List.of("types", "api", "api-test", "permissions", "routes", "page", "form", "detail"));
        if (Boolean.TRUE.equals(model.get("hasDecimal"))) selectedTemplates.addAll(List.of("decimal", "decimal-test"));
        for (String template : selectedTemplates) {
            String filename = switch (template) {
                case "page", "form", "detail" -> stem + Character.toUpperCase(template.charAt(0)) + template.substring(1) + ".tsx";
                case "routes" -> feature + ".routes.tsx";
                case "api-test" -> feature + ".api.test.ts";
                case "decimal-test" -> feature + ".decimal.test.ts";
                default -> feature + "." + template + ".ts";
            };
            files.add(new GenerationPlanService.RenderedFile(directory + filename, "frontend-" + template, tableName,
                    templates.render(template, model)));
        }
        ModuleMetadata metadata = metadata(tableName, feature, frontend.getRoute(), model, apiFields, operations);
        files.add(new GenerationPlanService.RenderedFile(directory + feature + ".metadata.json", "frontend-metadata", tableName,
                (json(metadata) + "\n").getBytes(StandardCharsets.UTF_8)));
        return metadata;
    }

    private Map<String, Object> enrichField(Map<String, String> field, CodegenConfigBO.FrontendFieldBO ui,
                                             Map<String, Map<String, String>> apiFields) {
        if (ui == null || !validText(ui.getLabel())) throw invalid("a label is required for UI field " + field.get("column"));
        String jsonType = field.get("jsonType");
        String control = ui.getControl();
        if (control == null || control.isBlank()) control = switch (jsonType) {
            case "NUMBER" -> "number";
            case "BOOLEAN" -> "switch";
            default -> "text";
        };
        boolean compatible = switch (control) {
            case "text" -> "STRING".equals(jsonType);
            case "decimal" -> "java.math.BigDecimal".equals(field.get("javaType")) && "STRING".equals(jsonType);
            case "number" -> "NUMBER".equals(jsonType);
            case "switch" -> "BOOLEAN".equals(jsonType);
            case "date" -> "java.time.LocalDate".equals(field.get("javaType"));
            case "select" -> "STRING".equals(jsonType) && ui.getOptions() != null && !ui.getOptions().isEmpty();
            default -> false;
        };
        if ("java.math.BigDecimal".equals(field.get("javaType")) && !"decimal".equals(control)) {
            throw invalid("BigDecimal fields require control=decimal for an exact string wire contract: " + field.get("column"));
        }
        if (!compatible || (!"select".equals(control) && ui.getOptions() != null && !ui.getOptions().isEmpty())) {
            throw invalid("control is incompatible with the backend wire type: " + field.get("column"));
        }
        List<Map<String, String>> options = new ArrayList<>();
        if (ui.getOptions() != null) ui.getOptions().forEach((value, label) -> {
            if (!validText(value) || !validText(label)) throw invalid("invalid select option: " + field.get("column"));
            options.add(Map.of("value", value, "label", label));
        });
        Map<String, String> policy = apiFields.get(field.get("javaName"));
        if (policy == null) throw invalid("backend field permissions are missing: " + field.get("column"));
        Map<String, Object> result = new LinkedHashMap<>(field);
        result.put("nameJson", json(field.get("javaName")));
        result.put("labelJson", json(ui.getLabel()));
        result.put("control", control);
        result.put("optionsJson", json(options));
        result.put("tsType", switch (jsonType) { case "NUMBER" -> "number"; case "BOOLEAN" -> "boolean"; default -> "string"; });
        result.put("integer", Set.of("Long", "Integer").contains(field.get("javaType")));
        result.put("resultKeyJson", json(policy.getOrDefault("resultKey", "")));
        result.put("createKeyJson", json(policy.getOrDefault("createKey", "")));
        result.put("updateKeyJson", json(policy.getOrDefault("updateKey", "")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private ModuleMetadata metadata(String table, String feature, String route, Map<String, Object> model,
                                    Map<String, Map<String, String>> fields, Map<String, Map<String, String>> operations) {
        Set<String> permissions = new LinkedHashSet<>((List<String>) model.get("pagePermissions"));
        List<ResourceReference> references = new ArrayList<>();
        operations.values().forEach(operation -> {
            permissions.add(operation.get("permission"));
            references.add(new ResourceReference(operation.get("resourceCode"), "CALLS"));
        });
        for (String kind : List.of("resultFields", "createFields", "updateFields")) {
            String keyName = kind.equals("resultFields") ? "resultKey" : kind.equals("createFields") ? "createKey" : "updateKey";
            String permissionName = kind.equals("resultFields") ? "permission" : kind.equals("createFields") ? "createPermission" : "updatePermission";
            String usage = kind.equals("resultFields") ? "TABLE_FIELD" : "FORM_FIELD";
            for (Map<String, Object> value : (List<Map<String, Object>>) model.get(kind)) {
                Map<String, String> policy = fields.get(value.get("javaName"));
                permissions.add(policy.get(permissionName));
                references.add(new ResourceReference(policy.get(keyName), usage));
                if (kind.equals("resultFields")) references.add(new ResourceReference(policy.get(keyName), "DETAIL_FIELD"));
            }
        }
        for (String field : List.of("id", "version")) {
            Map<String, String> policy = fields.get(field);
            permissions.add(policy.get("permission"));
            references.add(new ResourceReference(policy.get("resultKey"), "DETAIL_FIELD"));
        }
        Map<String, String> version = fields.get("expectedVersion");
        permissions.add(version.get("updatePermission"));
        references.add(new ResourceReference(version.get("updateKey"), "FORM_FIELD"));
        if (permissions.contains(null) || permissions.contains("") || references.stream().anyMatch(value -> value.key() == null || value.key().isBlank())) {
            throw invalid("backend permission metadata is incomplete: " + table);
        }
        return new ModuleMetadata(table, feature, route, List.copyOf(permissions), List.copyOf(references));
    }

    private static void validateModules(List<ModuleMetadata> modules) {
        Set<String> names = new LinkedHashSet<>(), routes = new LinkedHashSet<>();
        for (ModuleMetadata module : modules) {
            if (!module.feature().matches("[a-z][a-z0-9-]*") || !names.add(module.feature().toLowerCase(Locale.ROOT))
                    || module.route() == null || !routes.add(module.route().toLowerCase(Locale.ROOT))) {
                throw invalid("frontend feature name or route collides with an existing generated feature");
            }
        }
    }

    private static Map<String, Object> registryModel(List<ModuleMetadata> modules) {
        Set<String> permissions = new java.util.TreeSet<>();
        Map<String, ResourceReference> references = new java.util.TreeMap<>();
        List<Map<String, Object>> imports = new ArrayList<>();
        for (int index = 0; index < modules.size(); index++) {
            ModuleMetadata module = modules.get(index);
            permissions.addAll(module.permissions());
            module.references().forEach(value -> references.put(value.key() + ':' + value.usage(), value));
            imports.add(Map.of("index", index, "importJson", json("../features/" + module.feature() + "/" + module.feature() + ".permissions")));
        }
        return Map.of("modules", imports, "permissionListJson", json(permissions), "referenceListJson", json(references.values()));
    }

    private static String fieldKey(Map<String, Map<String, String>> fields, String field, String key) {
        Map<String, String> policy = fields.get(field);
        if (policy == null || policy.get(key) == null || policy.get(key).isBlank()) throw invalid("backend field key is missing: " + field);
        return json(policy.get(key));
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 128
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException failure) { throw new IllegalStateException("frontend metadata serialization failed", failure); }
    }

    private static FrontendConfigurationException invalid(String message) {
        return new FrontendConfigurationException(message);
    }

    public record ResourceReference(String key, String usage) { }
    public record ModuleMetadata(String table, String feature, String route, List<String> permissions,
                                 List<ResourceReference> references) { }
    public static final class FrontendConfigurationException extends RuntimeException {
        public FrontendConfigurationException(String message) { super(message); }
    }
}
