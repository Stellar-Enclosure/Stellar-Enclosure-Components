package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.codegen.cli.CodegenCommand;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.model.CodegenProfileEnum;
import top.egon.cola.component.codegen.update.GenerationPlanService;
import top.egon.cola.component.codegen.update.GenerationStateRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendGenerationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void plansAndAppliesReactFeatureWithoutOverwritingEdits(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend");
        Path frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        Path configFile = temp.resolve("codegen.json");
        MAPPER.writeValue(configFile.toFile(), config(backend, frontend));

        CommandResult plan = run("plan", "--config", configFile.toString(), "--target", "frontend");
        assertEquals(0, plan.code(), plan.error());
        String planId = MAPPER.readTree(plan.output()).path("planId").asText();
        CommandResult applied = run("apply", "--config", configFile.toString(), "--target", "frontend", "--plan", planId);
        assertEquals(0, applied.code(), applied.error());
        Path page = frontend.resolve("src/features/orders/OrdersPage.tsx");
        assertTrue(Files.readString(page).contains("PageTemplate"));
        assertTrue(Files.readString(page).contains("ActionGuard"));
        assertTrue(Files.readString(frontend.resolve("src/features/orders/OrdersForm.tsx")).contains("useFieldAccess"));
        assertTrue(Files.readString(frontend.resolve("src/features/orders/orders.api.ts")).contains("{allowEmptyResponse}"));
        assertTrue(Files.readString(frontend.resolve("src/features/orders/orders.api.ts")).contains("Response envelope is invalid"));
        assertTrue(Files.readString(frontend.resolve("src/generated/permissions.ts")).contains("orders.permissions"));
        assertFalse(Files.exists(backend.resolve("src/features/orders")));

        Files.writeString(page, Files.readString(page) + "\n// custom business behavior\n");
        CodegenConfigBO changed = config(backend, frontend);
        changed.getFrontend().getTables().get("orders").setTitle("订单维护");
        MAPPER.writeValue(configFile.toFile(), changed);
        CommandResult conflictPlan = run("plan", "--config", configFile.toString(), "--target", "frontend");
        assertEquals(0, conflictPlan.code(), conflictPlan.error());
        assertTrue(conflictPlan.output().contains("CONFLICT"));
        String conflictId = MAPPER.readTree(conflictPlan.output()).path("planId").asText();
        assertEquals(4, run("apply", "--config", configFile.toString(), "--target", "frontend", "--plan", conflictId).code());
        assertTrue(Files.readString(page).contains("custom business behavior"));
        assertTrue(Files.readString(frontend.resolve("src/features/orders/orders.permissions.ts")).contains("订单管理"));
    }

    @Test
    void addsAnotherFeatureWithoutLosingExistingRegistry(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend");
        Path frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        String orders = Files.readString(Path.of("src/test/resources/ddl/schema.sql"));
        Path schema = temp.resolve("schema.sql");
        Files.writeString(schema, orders + "\n" + orders.replace("orders", "products"));
        Path file = temp.resolve("codegen.json");
        CodegenConfigBO first = config(backend, frontend);
        first.getInput().setSchemaFiles(List.of(schema.toString()));
        MAPPER.writeValue(file.toFile(), first);
        CommandResult firstPlan = run("plan", "--config", file.toString(), "--target", "frontend");
        assertEquals(0, firstPlan.code(), firstPlan.error());
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "frontend", "--plan",
                MAPPER.readTree(firstPlan.output()).path("planId").asText()).code());

        CodegenConfigBO second = config(backend, frontend);
        second.getInput().setSchemaFiles(List.of(schema.toString()));
        second.setTables(List.of("products"));
        second.setDomainMethods(Map.of("products", TestContracts.methods("Products")));
        second.getApiContract().setPermissionReferences(TestContracts.permissions("products"));
        second.getApiContract().setTables(Map.of("products", TestContracts.apiTable("products", "/api/products", "code")));
        second.getFrontend().setTables(Map.of("products", CodegenConfigBO.FrontendTableBO.builder()
                .title("商品管理").route("/products")
                .fields(Map.of("code", CodegenConfigBO.FrontendFieldBO.builder().label("商品编号").control("text").build())).build()));
        MAPPER.writeValue(file.toFile(), second);
        CommandResult secondPlan = run("plan", "--config", file.toString(), "--target", "frontend");
        assertEquals(0, secondPlan.code(), secondPlan.error());
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "frontend", "--plan",
                MAPPER.readTree(secondPlan.output()).path("planId").asText()).code());
        String registry = Files.readString(frontend.resolve("src/generated/permissions.ts"));
        assertTrue(registry.contains("orders.permissions"));
        assertTrue(registry.contains("products.permissions"));
        assertTrue(Files.exists(frontend.resolve("src/features/orders/OrdersPage.tsx")));

        second.getFrontend().getTables().get("products").setRoute("/orders");
        MAPPER.writeValue(file.toFile(), second);
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "frontend").code());
    }

    @Test
    void rejectsMissingLabelsAndUnscannedRegistry(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend");
        Path frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        CodegenConfigBO config = config(backend, frontend);
        config.getFrontend().getTables().get("orders").setFields(Map.of());
        Path file = temp.resolve("codegen.json");
        MAPPER.writeValue(file.toFile(), config);
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "frontend").code());
        Files.writeString(frontend.resolve("permission-manifest.config.json"), "{\"ownedKeyPrefixes\":[\"app.\"],\"modules\":{}}");
        MAPPER.writeValue(file.toFile(), config(backend, frontend));
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "frontend").code());
    }

    @Test
    void selectsTheConfiguredHttpResponseContract(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend"), frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        CodegenConfigBO config = config(backend, frontend);
        config.getFrontend().setResponseMode("direct");
        Path file = temp.resolve("codegen.json");
        MAPPER.writeValue(file.toFile(), config);
        CommandResult plan = run("plan", "--config", file.toString(), "--target", "frontend");
        assertEquals(0, plan.code(), plan.error());
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "frontend", "--plan",
                MAPPER.readTree(plan.output()).path("planId").asText()).code());
        String api = Files.readString(frontend.resolve("src/features/orders/orders.api.ts"));
        assertTrue(api.contains("return response as T"));
        assertFalse(api.contains("Response envelope is invalid"));
    }

    @Test
    void generatesAnExactDecimalWireContract(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend"), frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        Path schema = temp.resolve("schema.sql");
        Files.writeString(schema, Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64), amount DECIMAL(20,2)"));
        CodegenConfigBO config = config(backend, frontend);
        config.getInput().setSchemaFiles(List.of(schema.toString()));
        config.setFieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder().create(List.of("code", "amount"))
                .update(List.of("code", "amount")).result(List.of("code", "amount"))
                .filter(List.of("code", "amount")).sort(List.of("id")).build());
        config.getApiContract().setTables(Map.of("orders", TestContracts.apiTable("orders", "/api/orders", "code", "amount")));
        config.getFrontend().getTables().get("orders").setFields(Map.of(
                "code", CodegenConfigBO.FrontendFieldBO.builder().label("订单编号").control("text").build(),
                "amount", CodegenConfigBO.FrontendFieldBO.builder().label("金额").control("decimal").build()));
        Path file = temp.resolve("codegen.json");
        MAPPER.writeValue(file.toFile(), config);
        CommandResult backendPlan = run("plan", "--config", file.toString(), "--target", "backend");
        assertEquals(0, backendPlan.code(), backendPlan.error());
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "backend", "--plan",
                MAPPER.readTree(backendPlan.output()).path("planId").asText()).code());
        Path view = backend.resolve("src/main/java/com/example/order/adapter/order/pojo/vo/OrdersVO.java");
        String java = Files.readString(view);
        assertTrue(java.contains("@JsonFormat(shape = JsonFormat.Shape.STRING)"));
        assertTrue(java.contains("@FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING"));
        CommandResult frontendPlan = run("plan", "--config", file.toString(), "--target", "frontend");
        assertEquals(0, frontendPlan.code(), frontendPlan.error());
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "frontend", "--plan",
                MAPPER.readTree(frontendPlan.output()).path("planId").asText()).code());
        assertTrue(Files.readString(frontend.resolve("src/features/orders/orders.types.ts")).contains("\"amount\"?: string"));
        assertTrue(Files.readString(frontend.resolve("src/features/orders/OrdersForm.tsx")).contains("inputMode=\"decimal\""));
        config.getFrontend().getTables().get("orders").getFields().get("amount").setControl("number");
        MAPPER.writeValue(file.toFile(), config);
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "frontend").code());
    }

    @Test
    void emitsExplicitBackendPermissionDefinitions(@TempDir Path temp) throws Exception {
        Path backend = temp.resolve("backend");
        Path frontend = temp.resolve("frontend");
        Files.createDirectories(backend);
        scaffold(frontend);
        CodegenConfigBO config = config(backend, frontend);
        config.getApiContract().setPermissionReferences(TestContracts.permissions("orders").stream()
                .filter(value -> !value.equals("fixture:orders:read")).toList());
        config.getApiContract().setGeneratedDefinitions(Map.of("fixture:orders:read",
                CodegenConfigBO.PermissionDefinitionBO.builder().meaning("Read order fields").labelPage("fixture.orders")
                        .labelKey("permission.read").defaultText("读取订单字段").build()));
        Path file = temp.resolve("codegen.json");
        MAPPER.writeValue(file.toFile(), config);
        CommandResult plan = run("plan", "--config", file.toString(), "--target", "backend");
        assertEquals(0, plan.code(), plan.error());
        String id = MAPPER.readTree(plan.output()).path("planId").asText();
        assertEquals(0, run("apply", "--config", file.toString(), "--target", "backend", "--plan", id).code());
        Path definition;
        try (var files = Files.walk(backend)) {
            definition = files.filter(path -> path.getFileName().toString().startsWith("GeneratedPermission"))
                    .findFirst().orElseThrow();
        }
        String source = Files.readString(definition);
        assertTrue(source.contains("@PermissionDefinition(code = \"fixture:orders:read\""));
        assertTrue(source.contains("@PermissionMessage"));
        assertTrue(source.contains("读取订单字段"));

        config.getApiContract().setPermissionReferences(TestContracts.permissions("orders"));
        MAPPER.writeValue(file.toFile(), config);
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "backend").code());
        config.getApiContract().setPermissionReferences(TestContracts.permissions("orders").stream()
                .map(value -> value.equals("fixture:orders:read") ? "fixture:orders:READ" : value).toList());
        MAPPER.writeValue(file.toFile(), config);
        assertEquals(2, run("plan", "--config", file.toString(), "--target", "backend").code());
    }

    private static CodegenConfigBO config(Path backend, Path frontend) {
        return CodegenConfigBO.builder().configVersion(2).profile(CodegenProfileEnum.LIGHT)
                .basePackage("com.example.order").domain("order").outputRoot(backend.toString())
                .input(CodegenConfigBO.InputBO.builder().mode("schema").schemaFiles(List.of("src/test/resources/ddl/schema.sql")).build())
                .tables(List.of("orders")).artifacts(List.of("backend-crud"))
                .fieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder().create(List.of("code")).update(List.of("code"))
                        .result(List.of("code")).filter(List.of("code")).sort(List.of("id")).build())
                .domainMethods(Map.of("orders", TestContracts.methods("Orders")))
                .apiContract(CodegenConfigBO.ApiContractBO.builder().existingErrorMapper("com.example.order.support.OrdersErrorMapper")
                        .transportMode("generate").permissionReferences(TestContracts.permissions("orders"))
                        .tables(Map.of("orders", TestContracts.apiTable("orders", "/api/orders", "code"))).build())
                .frontend(CodegenConfigBO.FrontendBO.builder().outputRoot(frontend.toString()).tables(Map.of("orders",
                        CodegenConfigBO.FrontendTableBO.builder().title("订单管理").route("/orders")
                                .fields(Map.of("code", CodegenConfigBO.FrontendFieldBO.builder().label("订单编号").control("text").build())).build())).build())
                .build();
    }

    private static void scaffold(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/generated"));
        String registry = "// owned by the React archetype\n";
        Files.writeString(root.resolve("src/generated/permissions.ts"), registry);
        Files.writeString(root.resolve("permission-manifest.config.json"), """
                {"ownedKeyPrefixes":["app."],"modules":{"app":{"entryPoints":["src/generated/permissions.ts"]}}}
                """);
        var artifact = CodegenPlanBO.ArtifactStateBO.builder().path("src/generated/permissions.ts")
                .artifact("frontend-registry").lastGeneratedInput("react-archetype")
                .previousGeneratedHash(GenerationPlanService.sha256(registry.getBytes(StandardCharsets.UTF_8)))
                .previousGeneratedHashPresent(true).build();
        new GenerationStateRepository().save(root, GenerationStateRepository.StateBO.builder().formatVersion(1).artifacts(List.of(artifact)).build());
    }

    private static CommandResult run(String... args) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = new CodegenCommand().run(args, new PrintStream(output), new PrintStream(error));
        return new CommandResult(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record CommandResult(int code, String output, String error) { }
}
