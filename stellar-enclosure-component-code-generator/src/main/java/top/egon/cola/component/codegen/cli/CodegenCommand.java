package top.egon.cola.component.codegen.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.codegen.ddl.DdlSchemaService;
import top.egon.cola.component.codegen.ddl.PostgreDdlAdapter;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.model.CodegenSchemaBO;
import top.egon.cola.component.codegen.profile.ProjectLayoutStrategy;
import top.egon.cola.component.codegen.template.FreeMarkerTemplateService;
import top.egon.cola.component.codegen.template.FrontendGenerationService;
import top.egon.cola.component.codegen.update.GenerationApplyService;
import top.egon.cola.component.codegen.update.GenerationPlanService;
import top.egon.cola.component.codegen.update.GenerationStateRepository;
import top.egon.cola.component.codegen.validation.CodegenConfigValidator;
import top.egon.cola.component.codegen.validation.GenerationScopeValidator;
import top.egon.cola.component.codegen.validation.OutputPathValidator;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import jakarta.validation.Validation;
import java.io.PrintStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Plain Java entry point. It does not start Spring or open a database.
 */
@Slf4j
public class CodegenCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final CodegenConfigValidator configValidator = new CodegenConfigValidator(
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator()));
    private final DdlSchemaService schemas = new DdlSchemaService(new PostgreDdlAdapter());
    private final GenerationScopeValidator scope = new GenerationScopeValidator();
    private final FreeMarkerTemplateService templates = new FreeMarkerTemplateService();
    private final FrontendGenerationService frontend = new FrontendGenerationService();
    private final GenerationStateRepository states = new GenerationStateRepository();
    private final OutputPathValidator paths = new OutputPathValidator();
    private final GenerationPlanService plans = new GenerationPlanService(states, paths);
    private final GenerationApplyService applyService = new GenerationApplyService(states, paths);

    public static void main(String[] args) {
        int code = new CodegenCommand().run(args, System.out, System.err);
        System.exit(code);
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args == null || args.length == 0) {
                return error(out, err, 2, "CONFIG_REQUIRED", "command is required");
            }
            Map<String, String> flags = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (!token.startsWith("--") || index + 1 >= args.length) {
                    return error(out, err, 2, "CONFIG_REQUIRED", "unknown or incomplete option " + token);
                }
                if (flags.containsKey(token)) {
                    return error(out, err, 2, "CONFIG_REQUIRED", "duplicate option " + token);
                }
                flags.put(token, args[++index]);
            }
            String target = flags.getOrDefault("--target", "backend");
            if (!Set.of("backend", "frontend").contains(target)) {
                return error(out, err, 2, "CONFIG_REQUIRED", "target must be backend or frontend");
            }
            if ("frontend".equals(target) && "retire-legacy".equals(args[0])) {
                return error(out, err, 2, "CONFIG_REQUIRED", "frontend retirement requires an explicit migration");
            }
            return switch (args[0]) {
                case "templates" -> templates(out);
                case "plan" -> plan(flags, out, err);
                case "apply" -> apply(flags, out, err);
                case "check" -> check(flags, out, err);
                case "recover" -> recover(flags, out, err);
                case "retire-legacy" -> retireLegacy(flags, out, err);
                default -> error(out, err, 2, "CONFIG_REQUIRED", "unknown command");
            };
        } catch (Exception exception) {
            if (exception instanceof FrontendGenerationService.FrontendConfigurationException failure) {
                return error(out, err, 2, "CONFIG_REQUIRED", failure.getMessage());
            }
            if (exception instanceof OutputPathValidator.PathValidationException failure) {
                return error(out, err, 2, failure.getCode(), failure.getMessage());
            }
            if (exception instanceof PostgreDdlAdapter.DdlParseException failure) {
                int code = "apply".equals(args[0]) ? 6 : 2;
                return error(out, err, code, code == 6 ? "STALE_PLAN" : failure.getCode(), failure.getMessage());
            }
            log.warn("codegen command failed: {}", exception.getClass().getSimpleName());
            return error(out, err, 7, "IO_FAILURE", exception.getMessage() == null ? "command failed" : exception.getMessage());
        }
    }

    private int templates(PrintStream out) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engine", "freemarker");
        body.put("engineVersion", "2.3.35");
        body.put("templateSetDigest", templates.getTemplateSetDigest());
        body.put("profiles", List.of("light", "web", "service"));
        List<String> artifacts = new ArrayList<>(List.of("po", "dao", "mapper-xml", "repo",
                "domain-model", "domain-query", "command", "query", "result", "converter",
                "domain-service", "domain-impl", "manage", "manage-impl", "controller", "event"));
        artifacts.sort(String::compareTo);
        body.put("artifacts", artifacts);
        body.put("presets", List.of("persistence-crud", "backend-crud"));
        body.put("frontendPreset", "react-admin");
        body.put("frontendTemplateSetDigest", frontend.templateDigest());
        body.put("eventConfiguration", "events.definitions");
        body.put("eventActions", List.of("create", "update", "delete", "delegate"));
        body.put("planFormatVersion", CodegenPlanBO.FORMAT_VERSION);
        out.println(MAPPER.writeValueAsString(body));
        return 0;
    }

    private int plan(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        Loaded loaded = load(flags, out, err);
        if (loaded.code() != 0) {
            return loaded.code();
        }
        List<GenerationPlanService.RenderedFile> rendered = render(loaded);
        List<String> legacy = legacyLayoutConflicts(loaded.root(), loaded.config(), rendered);
        if (!legacy.isEmpty()) {
            return error(out, err, 4, "LEGACY_LAYOUT_CONFLICT",
                    "review and migrate legacy generated paths before planning: " + String.join(", ", legacy));
        }
        List<String> omittedEvents = omittedEventPaths(loaded.root(), loaded.config(), rendered);
        if (!omittedEvents.isEmpty()) {
            return error(out, err, 4, "EVENT_SCOPE_CONFLICT",
                    "review previously generated event paths before changing their scope: " + String.join(", ", omittedEvents));
        }
        CodegenPlanBO plan = buildPlan(loaded, rendered);
        Files.createDirectories(planFile(loaded.root(), plan.getPlanId()).getParent());
        MAPPER.writeValue(planFile(loaded.root(), plan.getPlanId()).toFile(), plan);
        Map<String, Object> body = summary(plan);
        out.println(MAPPER.writeValueAsString(body));
        return 0;
    }

    private int apply(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        if (!flags.containsKey("--plan") || !flags.containsKey("--config")) {
            return error(out, err, 2, "CONFIG_REQUIRED", "apply requires --config and --plan");
        }
        if (!flags.get("--plan").matches("[0-9a-f]{64}")) {
            return error(out, err, 2, "CONFIG_REQUIRED", "plan id must be a SHA-256 digest");
        }
        Loaded loaded = load(flags, out, err);
        if (loaded.code() != 0) {
            return loaded.code();
        }
        List<GenerationPlanService.RenderedFile> rendered = render(loaded);
        List<String> legacy = legacyLayoutConflicts(loaded.root(), loaded.config(), rendered);
        if (!legacy.isEmpty()) {
            return error(out, err, 4, "LEGACY_LAYOUT_CONFLICT",
                    "review and migrate legacy generated paths before applying: " + String.join(", ", legacy));
        }
        List<String> omittedEvents = omittedEventPaths(loaded.root(), loaded.config(), rendered);
        if (!omittedEvents.isEmpty()) {
            return error(out, err, 4, "EVENT_SCOPE_CONFLICT",
                    "review previously generated event paths before changing their scope: " + String.join(", ", omittedEvents));
        }
        Path file = planFile(loaded.root(), flags.get("--plan"));
        if (!Files.exists(file)) {
            return error(out, err, 2, "CONFIG_REQUIRED", "plan was not found");
        }
        CodegenPlanBO plan = MAPPER.readValue(file.toFile(), CodegenPlanBO.class);
        if (!flags.get("--plan").equals(plan.getPlanId()) || !GenerationPlanService.hasValidId(plan)) {
            return error(out, err, 6, "STALE_PLAN", "plan id does not match");
        }
        GenerationApplyService.Result result = applyService.apply(loaded.root(), plan, Set.of(), () -> {
            try {
                Loaded fresh = load(flags, out, err);
                if (fresh.code() != 0) {
                    return null;
                }
                List<GenerationPlanService.RenderedFile> freshFiles = render(fresh);
                if (!legacyLayoutConflicts(fresh.root(), fresh.config(), freshFiles).isEmpty()
                        || !omittedEventPaths(fresh.root(), fresh.config(), freshFiles).isEmpty()) {
                    return null;
                }
                return new GenerationApplyService.CurrentInputs(fresh.schema().getInputFingerprint(),
                        configFingerprint(fresh.config()), templateDigest(fresh.target()),
                        componentFingerprint(), freshFiles);
            } catch (Exception failure) {
                throw new IllegalStateException("generation inputs changed", failure);
            }
        }, -1);
        out.println(MAPPER.writeValueAsString(Map.of("status", result.status(), "detail", result.detail())));
        return switch (result.status()) {
            case "APPLIED", "NO_CHANGE" -> 0;
            case "CONFLICT" -> 4;
            case "DESTRUCTIVE_ACTION" -> 5;
            case "STALE_PLAN" -> 6;
            case "RECOVERY_REQUIRED" -> 7;
            default -> 2;
        };
    }

    private int check(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        Loaded loaded = load(flags, out, err);
        if (loaded.code() != 0) {
            return loaded.code();
        }
        List<GenerationPlanService.RenderedFile> files = render(loaded);
        List<String> legacy = legacyLayoutConflicts(loaded.root(), loaded.config(), files);
        List<String> omittedEvents = omittedEventPaths(loaded.root(), loaded.config(), files);
        boolean drift = !legacy.isEmpty() || !omittedEvents.isEmpty();
        for (GenerationPlanService.RenderedFile rendered : files) {
            Path target = loaded.root().resolve(rendered.relativePath());
            byte[] disk = Files.exists(target) ? Files.readAllBytes(target) : null;
            if (disk == null || !GenerationPlanService.sha256(disk).equals(GenerationPlanService.sha256(rendered.bytes()))) {
                drift = true;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", drift ? "DRIFT" : "CLEAN");
        if (!legacy.isEmpty()) {
            summary.put("legacyPaths", legacy);
        }
        if (!omittedEvents.isEmpty()) {
            summary.put("omittedEventPaths", omittedEvents);
        }
        if (!legacy.isEmpty() || !omittedEvents.isEmpty()) {
            GenerationStateRepository.StateBO state = states.load(loaded.root());
            Set<String> owned = new LinkedHashSet<>();
            state.getArtifacts().forEach(artifact -> owned.add(artifact.getPath()));
            Set<String> reviewed = new LinkedHashSet<>(legacy);
            reviewed.addAll(omittedEvents);
            reviewed.retainAll(owned);
            if (!reviewed.isEmpty()) {
                summary.put("retirementReview", new GenerationApplyService.RetirementReview(
                        loaded.schema().getInputFingerprint(), configFingerprint(loaded.config()), states.fingerprint(state),
                        templateDigest(loaded.target()), componentFingerprint(), reviewed.stream().sorted().toList()));
            }
        }
        out.println(MAPPER.writeValueAsString(summary));
        return drift ? 8 : 0;
    }

    private int recover(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        Loaded loaded = load(flags, out, err);
        if (loaded.code() != 0) {
            return loaded.code();
        }
        GenerationApplyService.Result result = applyService.recover(loaded.root(), "rollback");
        out.println(MAPPER.writeValueAsString(Map.of("status", result.status(), "detail", result.detail())));
        return "CONFLICT".equals(result.status()) ? 4 : 0;
    }

    private int retireLegacy(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        if (!flags.containsKey("--config") || !flags.containsKey("--paths-file")) {
            return error(out, err, 2, "CONFIG_REQUIRED", "retire-legacy requires --config and --paths-file");
        }
        GenerationApplyService.RetirementReview review;
        try {
            review = MAPPER.readValue(Path.of(flags.get("--paths-file")).toFile(),
                    GenerationApplyService.RetirementReview.class);
        } catch (IOException failure) {
            return error(out, err, 2, "CONFIG_REQUIRED", "paths file must contain the reviewed retirement object");
        }
        if (review == null || review.paths() == null || review.paths().isEmpty()
                || review.inputFingerprint() == null
                || !review.inputFingerprint().matches("[0-9a-f]{64}(\\n[0-9a-f]{64})*")
                || !sha256Value(review.configFingerprint())
                || !sha256Value(review.stateFingerprint()) || !sha256Value(review.templateFingerprint())
                || !sha256Value(review.componentFingerprint())) {
            return error(out, err, 2, "CONFIG_REQUIRED", "retirement review must include paths and input fingerprints");
        }
        for (String path : review.paths()) {
            if (path == null || path.isBlank()) {
                return error(out, err, 2, "CONFIG_REQUIRED", "every retired path must be a nonblank string");
            }
        }
        try {
            paths.checkDistinct(review.paths());
        } catch (OutputPathValidator.PathValidationException failure) {
            return error(out, err, 2, failure.getCode(), failure.getMessage());
        }
        Loaded loaded = load(flags, out, err);
        if (loaded.code() != 0) {
            return loaded.code();
        }
        GenerationApplyService.Result result = applyService.retireLegacy(loaded.root(), review, () -> {
            try {
                Loaded fresh = load(flags, out, err);
                if (fresh.code() != 0 || !fresh.root().toAbsolutePath().normalize()
                        .equals(loaded.root().toAbsolutePath().normalize())) {
                    return null;
                }
                List<GenerationPlanService.RenderedFile> files = render(fresh);
                Set<String> eligible = new LinkedHashSet<>(legacyLayoutConflicts(fresh.root(), fresh.config(), files));
                eligible.addAll(omittedEventPaths(fresh.root(), fresh.config(), files));
                return new GenerationApplyService.RetirementInputs(fresh.schema().getInputFingerprint(),
                        configFingerprint(fresh.config()), templateDigest(fresh.target()), componentFingerprint(), eligible);
            } catch (Exception failure) {
                throw new IllegalStateException("generation inputs changed", failure);
            }
        });
        out.println(MAPPER.writeValueAsString(Map.of("status", result.status(), "detail", result.detail())));
        return switch (result.status()) {
            case "APPLIED", "NO_CHANGE" -> 0;
            case "CONFLICT" -> 4;
            case "STALE_PLAN" -> 6;
            case "RECOVERY_REQUIRED" -> 7;
            default -> 2;
        };
    }

    private static boolean sha256Value(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private CodegenPlanBO buildPlan(Loaded loaded, List<GenerationPlanService.RenderedFile> rendered) {
        return plans.plan(loaded.root(), rendered, loaded.config().getProfile(), loaded.schema().getInputFingerprint(),
                configFingerprint(loaded.config()), loaded.schema().getVersionChecksumPrefix(), templateDigest(loaded.target()),
                componentFingerprint(), List.of());
    }

    private String templateDigest(String target) {
        return "frontend".equals(target) ? frontend.templateDigest() : templates.getTemplateSetDigest();
    }

    private List<GenerationPlanService.RenderedFile> render(Loaded loaded) {
        return "frontend".equals(loaded.target()) ? frontend.render(loaded.config(), loaded.schema(), loaded.root())
                : render(loaded.config(), loaded.schema());
    }

    private List<String> legacyLayoutConflicts(Path root, CodegenConfigBO config,
                                               List<GenerationPlanService.RenderedFile> rendered) {
        Set<String> owned = new LinkedHashSet<>();
        List<CodegenPlanBO.ArtifactStateBO> previous = states.load(root).getArtifacts();
        for (CodegenPlanBO.ArtifactStateBO artifact : previous) {
            if (artifact.getPath() != null) {
                paths.check(root, artifact.getPath());
                owned.add(artifact.getPath());
            }
        }
        Map<ArtifactIdentity, Set<String>> selectedByIdentity = new LinkedHashMap<>();
        for (GenerationPlanService.RenderedFile file : rendered) {
            ArtifactIdentity key = artifactIdentity(file.artifact(), file.table(), file.relativePath());
            selectedByIdentity.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(file.relativePath());
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (GenerationPlanService.RenderedFile file : rendered) {
            if ("domain-service".equals(file.artifact())) {
                Path target = root.resolve(file.relativePath());
                CodegenConfigBO.DomainMethodsBO methods = config.getDomainMethods().get(file.table());
                if (methods != null) {
                    paths.check(root, file.relativePath());
                    if (Files.exists(target)) {
                        try {
                            String source = Files.readString(target);
                            if (!GenerationScopeValidator.hasDomainMethods(source, methods)) {
                                conflicts.add(file.relativePath());
                            }
                        } catch (IOException failure) {
                            throw new IllegalStateException("domain contract could not be reviewed", failure);
                        }
                    } else if (owned.contains(file.relativePath())) {
                        conflicts.add(file.relativePath());
                    }
                }
            }
            String legacy = legacyPath(config, file.relativePath());
            if (legacy == null || legacy.equals(file.relativePath())) {
                continue;
            }
            paths.check(root, legacy);
            if (owned.contains(legacy) || Files.exists(root.resolve(legacy))) {
                conflicts.add(legacy);
            }
        }
        for (CodegenPlanBO.ArtifactStateBO artifact : previous) {
            if (artifact.getPath() == null) {
                continue;
            }
            if ("permission-mvc-config".equals(artifact.getArtifact()) && config.getArtifacts().contains("controller")
                    && rendered.stream().noneMatch(file -> file.relativePath().equals(artifact.getPath()))) {
                conflicts.add(artifact.getPath());
            }
            Set<String> current = selectedByIdentity.get(artifactIdentity(
                    artifact.getArtifact(), artifact.getLogicalTable(), artifact.getPath()));
            if (current != null && !current.contains(artifact.getPath())) {
                paths.check(root, artifact.getPath());
                conflicts.add(artifact.getPath());
            }
        }
        return conflicts.stream().sorted().toList();
    }

    private static ArtifactIdentity artifactIdentity(String artifact, String table, String path) {
        return new ArtifactIdentity(artifact, table, Path.of(path).getFileName().toString());
    }

    private record ArtifactIdentity(String artifact, String table, String fileName) {
    }

    private List<String> omittedEventPaths(Path root, CodegenConfigBO config,
                                           List<GenerationPlanService.RenderedFile> rendered) {
        if (!config.getArtifacts().contains("command") && !config.getArtifacts().contains("event")) {
            return List.of();
        }
        Set<String> selected = new LinkedHashSet<>();
        for (GenerationPlanService.RenderedFile file : rendered) {
            if ("event".equals(file.artifact())) {
                selected.add(file.relativePath());
            }
        }
        Set<String> omitted = new LinkedHashSet<>();
        for (CodegenPlanBO.ArtifactStateBO artifact : states.load(root).getArtifacts()) {
            if ("event".equals(artifact.getArtifact()) && ("__event__".equals(artifact.getLogicalTable())
                    || config.getTables().contains(artifact.getLogicalTable()))
                    && !selected.contains(artifact.getPath())) {
                omitted.add(artifact.getPath());
            }
        }
        return omitted.stream().sorted().toList();
    }

    private static String legacyPath(CodegenConfigBO config, String current) {
        String marker = "src/main/java/" + config.getBasePackage().replace('.', '/') + "/";
        int start = current.indexOf(marker);
        if (start < 0) {
            return null;
        }
        String prefix = current.substring(0, start + marker.length());
        String path = current.substring(start + marker.length());
        String domain = config.getDomain() + "/";
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("domain/" + domain + "vos/", "domain/" + domain);
        previous.put("domain/" + domain + "service/", "domain/" + domain);
        previous.put("application/" + domain + "pojo/convertor/", "application/" + domain + "converter/");
        for (String part : List.of("pojo/command/", "pojo/query/", "pojo/result/", "pojo/event/", "manage/impl/",
                "manage/", "executor/command/", "executor/query/", "executor/event/")) {
            previous.put("application/" + domain + part, "application/" + domain);
        }
        for (String part : List.of("controller/", "event/", "pojo/dto/", "pojo/vo/", "pojo/convertor/")) {
            previous.put("adapter/" + domain + part, "adapter/" + domain);
        }
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return prefix + entry.getValue() + path.substring(entry.getKey().length());
            }
        }
        return null;
    }

    private List<GenerationPlanService.RenderedFile> render(CodegenConfigBO config, CodegenSchemaBO schema) {
        ProjectLayoutStrategy layout = ProjectLayoutStrategy.resolve(config.getProfile());
        List<GenerationPlanService.RenderedFile> files = new ArrayList<>();
        for (CodegenSchemaBO.TableBO table : schema.getTables()) {
            for (String artifact : config.getArtifacts()) {
                Map<String, Object> model = scope.renderModel(config, table, artifact);
                switch (artifact) {
                    case "event" -> { }
                    case "command" -> {
                        emit(files, config, layout, table, model, artifact, "command", "create", "createCommandType", "command");
                        emit(files, config, layout, table, model, artifact, "command", "update", "updateCommandType", "command");
                        emit(files, config, layout, table, model, artifact, "command", "delete", "deleteCommandType", "command");
                        emit(files, config, layout, table, model, artifact, "executor", "create", "createExecutorType", "command-executor");
                        emit(files, config, layout, table, model, artifact, "executor", "update", "updateExecutorType", "command-executor");
                        emit(files, config, layout, table, model, artifact, "executor", "delete", "deleteExecutorType", "command-executor");
                    }
                    case "query" -> {
                        emit(files, config, layout, table, model, artifact, "query", "detail", "detailQueryType", "query");
                        emit(files, config, layout, table, model, artifact, "query", "page", "pageQueryType", "query");
                        emit(files, config, layout, table, model, artifact, "executor", "detail", "detailExecutorType", "query-executor");
                        emit(files, config, layout, table, model, artifact, "executor", "page", "pageExecutorType", "query-executor");
                    }
                    case "converter" -> {
                        emit(files, config, layout, table, model, artifact, "converter", "persistence", "persistenceConverterType", "persistence-converter");
                        emit(files, config, layout, table, model, artifact, "converter", "create", "createConverterType", "application-converter");
                        emit(files, config, layout, table, model, artifact, "converter", "update", "updateConverterType", "application-converter");
                        emit(files, config, layout, table, model, artifact, "converter", "delete", "deleteConverterType", "application-converter");
                        emit(files, config, layout, table, model, artifact, "converter", "result", "resultConverterType", "application-converter");
                        emit(files, config, layout, table, model, artifact, "converter", "page", "pageConverterType", "application-converter");
                    }
                    case "controller" -> {
                        emit(files, config, layout, table, model, artifact, "controller", "main", "controllerType", "controller");
                        emit(files, config, layout, table, model, artifact, "dto", "create", "createDtoType", "adapter-dto");
                        emit(files, config, layout, table, model, artifact, "dto", "update", "updateDtoType", "adapter-dto");
                        emit(files, config, layout, table, model, artifact, "dto", "page", "pageDtoType", "adapter-dto");
                        emit(files, config, layout, table, model, artifact, "view", "result", "viewType", "adapter-vo");
                        emit(files, config, layout, table, model, artifact, "adapter", "converter", "adapterConverterType", "adapter-converter");
                    }
                    default -> {
                        String typeKey = switch (artifact) {
                            case "po" -> "poType";
                            case "dao", "mapper-xml" -> "daoType";
                            case "repo" -> "repoType";
                            case "domain-model" -> "domainType";
                            case "domain-query" -> "domainQueryType";
                            case "result" -> "resultType";
                            case "domain-service" -> "domainServiceType";
                            case "domain-impl" -> "domainImplType";
                            case "manage" -> "manageType";
                            case "manage-impl" -> "manageImplType";
                            default -> throw new IllegalArgumentException("unsupported artifact " + artifact);
                        };
                        String suffix = "mapper-xml".equals(artifact) ? ".xml" : ".java";
                        files.add(new GenerationPlanService.RenderedFile(
                                layout.relativePath(config, artifact, model.get(typeKey) + suffix),
                                artifact, table.getLogicalName(), templates.render(artifact, model)));
                    }
                }
            }
            if (config.getEvents() != null && config.getEvents().isEnabled()) {
                for (CodegenConfigBO.EventDefinitionBO event : config.getEvents().getDefinitions()) {
                    if (table.getLogicalName().equals(event.getTable())) {
                        Map<String, Object> model = scope.eventModel(config, table, event);
                        emit(files, config, layout, table, model, "event", "event", "inbound", "eventType", "event");
                        emit(files, config, layout, table, model, "event", "event", "inbound", "eventExecutorType", "event-executor");
                        if (!"delegate".equals(event.getAction())) {
                            emit(files, config, layout, table, model, "event", "event", "inbound", "eventConverterType", "event-converter");
                        }
                        emit(files, config, layout, table, model, "event", "event", "inbound", "eventHandlerType", "event-handler");
                    }
                }
            }
        }
        if (config.getEvents() != null && config.getEvents().isEnabled()) {
            for (CodegenConfigBO.EventDefinitionBO event : config.getEvents().getDefinitions()) {
                if ("delegate".equals(event.getAction()) && (event.getTable() == null || event.getTable().isBlank())) {
                    Map<String, Object> model = scope.eventModel(config, null, event);
                    CodegenSchemaBO.TableBO eventScope = CodegenSchemaBO.TableBO.builder().logicalName("__event__").build();
                    emit(files, config, layout, eventScope, model, "event", "event", "inbound", "eventType", "event");
                    emit(files, config, layout, eventScope, model, "event", "event", "inbound", "eventExecutorType", "event-executor");
                    emit(files, config, layout, eventScope, model, "event", "event", "inbound", "eventHandlerType", "event-handler");
                }
            }
        }
        if (config.getArtifacts().contains("controller") && config.getApiContract() != null
                && "generate".equals(config.getApiContract().getTransportMode())) {
            String type = "CqersPermissionMvcConfiguration";
            Map<String, Object> model = Map.of("permissionMvcPackage", layout.packageName(config, "permission-mvc-config"));
            files.add(new GenerationPlanService.RenderedFile(
                    layout.relativePath(config, "permission-mvc-config", type + ".java"),
                    "permission-mvc-config", "__security__", templates.render("permission-mvc-config", model)));
        }
        if (config.getArtifacts().contains("controller") && config.getApiContract() != null
                && config.getApiContract().getGeneratedDefinitions() != null) {
            config.getApiContract().getGeneratedDefinitions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String name = "GeneratedPermission" + GenerationPlanService.sha256(entry.getKey().getBytes(StandardCharsets.UTF_8)).substring(0, 20);
                var definition = entry.getValue();
                Map<String, Object> model = Map.of("permissionPackage", layout.packageName(config, "controller"),
                        "permissionType", name, "codeJson", jsonLiteral(entry.getKey()),
                        "meaningJson", jsonLiteral(definition.getMeaning()), "labelPageJson", jsonLiteral(definition.getLabelPage()),
                        "labelKeyJson", jsonLiteral(definition.getLabelKey()), "defaultTextJson", jsonLiteral(definition.getDefaultText()));
                files.add(new GenerationPlanService.RenderedFile(layout.relativePath(config, "controller", name + ".java"),
                        "permission-definition", entry.getKey(), templates.render("permission-definition", model)));
            });
        }
        for (GenerationPlanService.RenderedFile file : files) {
            if (file.relativePath().endsWith(".java")) {
                paths.checkJavaSource(file.relativePath(), file.bytes());
            }
        }
        return files;
    }

    private void emit(List<GenerationPlanService.RenderedFile> files, CodegenConfigBO config,
                      ProjectLayoutStrategy layout, CodegenSchemaBO.TableBO table, Map<String, Object> model,
                      String artifact, String variantKey, String variant, String typeKey, String pathArtifact) {
        Map<String, Object> context = new LinkedHashMap<>(model);
        context.put(variantKey + "Kind", variant);
        String template = switch (pathArtifact) {
            case "command-executor", "query-executor", "event", "event-executor", "event-converter", "event-handler",
                    "adapter-dto", "adapter-vo", "adapter-converter" -> pathArtifact;
            default -> artifact;
        };
        files.add(new GenerationPlanService.RenderedFile(
                layout.relativePath(config, pathArtifact, model.get(typeKey) + ".java"),
                artifact, table.getLogicalName(), templates.render(template, context)));
    }

    private Loaded load(Map<String, String> flags, PrintStream out, PrintStream err) throws Exception {
        String target = flags.getOrDefault("--target", "backend");
        if (!flags.containsKey("--config")) {
            error(out, err, 2, "CONFIG_REQUIRED", "--config is required");
            return new Loaded(2, null, null, null, target);
        }
        CodegenConfigBO config = MAPPER.readValue(Path.of(flags.get("--config")).toFile(), CodegenConfigBO.class);
        List<CodegenPlanBO.DiagnosticBO> diagnostics = configValidator.validate(config);
        if (!diagnostics.isEmpty()) {
            err.println(MAPPER.writeValueAsString(Map.of("code", diagnostics.get(0).getCode(), "message", diagnostics.get(0).getMessage())));
            return new Loaded(codeFor(diagnostics.get(0).getCode()), null, null, null, target);
        }
        config = configValidator.normalize(config);
        Path root = Path.of(config.getOutputRoot());
        if ("frontend".equals(target)) {
            if (config.getConfigVersion() == null || config.getConfigVersion() != 2 || config.getFrontend() == null
                    || config.getFrontend().getOutputRoot() == null || config.getFrontend().getOutputRoot().isBlank()) {
                return new Loaded(error(out, err, 2, "CONFIG_REQUIRED", "frontend target requires configVersion 2 and frontend.outputRoot"), null, null, null, target);
            }
            Path frontendRoot = Path.of(config.getFrontend().getOutputRoot()).toAbsolutePath().normalize();
            Path backendRoot = root.toAbsolutePath().normalize();
            if (Files.exists(frontendRoot)) frontendRoot = frontendRoot.toRealPath();
            if (Files.exists(backendRoot)) backendRoot = backendRoot.toRealPath();
            if (frontendRoot.startsWith(backendRoot) || backendRoot.startsWith(frontendRoot)) {
                return new Loaded(error(out, err, 2, "CONFIG_REQUIRED", "frontend and backend output roots must be separate"), null, null, null, target);
            }
            root = frontendRoot;
        }
        GenerationStateRepository.StateBO state = states.load(root);
        List<String> observedPrefix = new ArrayList<>();
        if (state.getVersionChecksumPrefix() != null && !state.getVersionChecksumPrefix().isBlank()) {
            for (String entry : state.getVersionChecksumPrefix().split("\\R")) {
                observedPrefix.add(entry.substring(entry.indexOf(':') + 1));
            }
        }
        boolean eventOnly = config.getInput() != null && "event".equals(config.getInput().getMode());
        CodegenSchemaBO schema = eventOnly ? CodegenSchemaBO.builder()
                .inputFingerprint(state.getLatestObservedSchema() == null
                        ? GenerationPlanService.sha256("event-only".getBytes(StandardCharsets.UTF_8)) : state.getLatestObservedSchema())
                .versionChecksumPrefix(state.getVersionChecksumPrefix()).build()
                : schemas.read(config, config.getInput().getLogicalPhysicalMappings(), observedPrefix);
        if (!eventOnly && (config.getTables() == null || config.getTables().isEmpty())) {
            return new Loaded(error(out, err, 2, "CONFIG_REQUIRED", "logicalTables is required"), null, null, null, target);
        }
        List<CodegenSchemaBO.TableBO> selected = new ArrayList<>();
        for (String tableName : config.getTables()) {
            CodegenSchemaBO.TableBO match = schema.getTables().stream()
                    .filter(table -> tableName.equals(table.getLogicalName())).findFirst().orElse(null);
            if (match == null) {
                return new Loaded(error(out, err, 2, "CONFIG_REQUIRED", "logical table is absent: " + tableName), null, null, null, target);
            }
            selected.add(match);
        }
        schema.setTables(selected);
        List<CodegenPlanBO.DiagnosticBO> scopeDiagnostics = scope.validate(config, schema);
        if (!scopeDiagnostics.isEmpty()) {
            err.println(MAPPER.writeValueAsString(Map.of("code", scopeDiagnostics.get(0).getCode(), "message", scopeDiagnostics.get(0).getMessage())));
            return new Loaded(3, null, null, null, target);
        }
        return new Loaded(0, config, schema, root, target);
    }

    private static String configFingerprint(CodegenConfigBO config) {
        try {
            return GenerationPlanService.sha256(MAPPER.writeValueAsBytes(config));
        } catch (IOException exception) {
            throw new IllegalStateException("configuration fingerprint failed", exception);
        }
    }

    private static String jsonLiteral(String value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalStateException("literal serialization failed", exception); }
    }

    private static String componentFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path codeSource = Path.of(CodegenCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String prefix = "top/egon/cola/component/codegen/";
            if (Files.isDirectory(codeSource)) {
                Path packageRoot = codeSource.resolve(prefix);
                try (var classes = Files.walk(packageRoot)) {
                    for (Path path : classes.filter(file -> file.toString().endsWith(".class"))
                            .sorted(Comparator.comparing(path -> codeSource.relativize(path).toString())).toList()) {
                        digest.update(codeSource.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(path));
                    }
                }
            } else {
                try (ZipFile jar = new ZipFile(codeSource.toFile())) {
                    for (var entry : jar.stream().filter(file -> file.getName().startsWith(prefix)
                            && file.getName().endsWith(".class")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                        digest.update(entry.getName().getBytes(StandardCharsets.UTF_8));
                        try (InputStream input = jar.getInputStream(entry)) {
                            digest.update(input.readAllBytes());
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | URISyntaxException exception) {
            throw new IllegalStateException("generator fingerprint failed", exception);
        }
    }

    private static int codeFor(String code) {
        return switch (code) {
            case "MISSING_TYPE", "BASE_FIELD_TYPE", "UNKNOWN_SQL_TYPE", "UNSUPPORTED_ID" -> 3;
            case "CONFLICT" -> 4;
            default -> 2;
        };
    }

    private static Map<String, Object> summary(CodegenPlanBO plan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", plan.getPlanId());
        body.put("fileCount", plan.getFiles().size());
        body.put("pending", plan.getPendingImpacts().size());
        body.put("files", plan.getFiles().stream().map(file -> Map.of(
                "path", file.getPath(), "artifact", file.getArtifact(), "operation", file.getOperation(),
                "candidateHash", file.getCandidateHash())).toList());
        return body;
    }

    private static Path planFile(Path root, String planId) {
        return root.resolve(".egon/codegen/plans").resolve(planId + ".json");
    }

    private static int error(PrintStream out, PrintStream err, int code, String stable, String message) {
        try {
            err.println(MAPPER.writeValueAsString(Map.of("code", stable,
                    "message", message == null ? "command failed" : message)));
        } catch (IOException exception) {
            err.println("{\"code\":\"IO_FAILURE\",\"message\":\"error serialization failed\"}");
        }
        return code;
    }

    private record Loaded(int code, CodegenConfigBO config, CodegenSchemaBO schema, Path root, String target) {
    }
}
