package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.codegen.cli.CodegenCommand;
import top.egon.cola.component.codegen.model.CodegenPlanBO;
import top.egon.cola.component.codegen.update.GenerationStateRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodegenCommandTest {

    private final CodegenCommand command = new CodegenCommand();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commandsReturnStableCodesWithoutStartingServices(@TempDir Path root) throws Exception {
        Invocation templates = invoke("templates");
        assertEquals(0, templates.code());
        JsonNode catalog = mapper.readTree(templates.out());
        assertEquals("freemarker", catalog.get("engine").asText());
        assertEquals("2.3.35", catalog.get("engineVersion").asText());
        assertEquals(2, catalog.get("planFormatVersion").asInt());
        assertTrue(catalog.path("artifacts").toString().contains("manage-impl"));
        assertEquals("events.definitions", catalog.path("eventConfiguration").asText());
        assertTrue(catalog.path("eventActions").toString().contains("delete"));
        assertTrue(templates.err().isBlank() || !templates.err().contains("SpringApplication"));

        assertEquals(2, invoke("nope").code());
        assertEquals(2, invoke("apply", "--config", "missing.json").code());

        Path config = writeConfig(root);
        Invocation check = invoke("check", "--config", config.toString());
        assertEquals(8, check.code());
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));

        Invocation plan = invoke("plan", "--config", config.toString());
        assertEquals(0, plan.code(), plan.err());
        String planId = mapper.readTree(plan.out()).get("planId").asText();
        Invocation apply = invoke("apply", "--config", config.toString(), "--plan", planId);
        assertEquals(0, apply.code(), apply.err());
        assertTrue(Files.exists(root.resolve("order-service-infrastructure/src/main/java/com/example/order/infrastructure/order/po/OrdersPO.java")));

        Invocation second = invoke("check", "--config", config.toString());
        assertEquals(0, second.code(), second.err());

        String source = Files.readString(Path.of("src/main/java/top/egon/cola/component/codegen/cli/CodegenCommand.java"));
        assertFalse(source.contains("SpringApplication"));
        assertFalse(source.contains("DriverManager"));
        assertFalse(source.contains("EgonColaPostgreDdlRunner"));
    }

    @Test
    void alterInputStopsPlanningForAgentOwnedChange(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Path schema = root.resolve("schema.sql");
        Files.writeString(schema, Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                + "\nALTER TABLE orders ADD COLUMN note VARCHAR(32);\n");
        String original = Files.readString(config);
        Files.writeString(config, original.replace(
                Path.of("src/test/resources/ddl/schema.sql").toAbsolutePath().toString(), schema.toString()));

        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(2, planned.code());
        assertTrue(planned.err().contains("UNSUPPORTED_DDL"));
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));
    }

    @Test
    void launcherBlocksMissingClasspath() throws Exception {
        Path script = Path.of("../scripts/stellar-enclosure-codegen.sh").toAbsolutePath().normalize();
        if (!Files.exists(script)) {
            script = Path.of("scripts/stellar-enclosure-codegen.sh").toAbsolutePath().normalize();
        }
        Process process = new ProcessBuilder("bash", script.toString(), "templates")
                .redirectErrorStream(false)
                .start();
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(7, code);
        assertTrue(error.contains("BLOCKED_TOOLING"));
    }

    @Test
    void backendCrudFlowsFromRepositoryThroughServicesToController(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        String json = Files.readString(config).replace(
                "[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]");
        Files.writeString(config, json);
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        String planId = mapper.readTree(planned.out()).path("planId").asText();
        assertEquals(33, mapper.readTree(planned.out()).path("fileCount").asInt());
        assertTrue(mapper.readTree(planned.out()).path("files").toString().contains("OrdersManageImpl.java"));
        assertTrue(mapper.readTree(planned.out()).path("files").toString().contains("CreateOrdersCmdExe.java"));
        assertTrue(mapper.readTree(planned.out()).path("files").toString().contains("OrdersPageQueryExe.java"));
        assertEquals(0, invoke("apply", "--config", config.toString(), "--plan", planId).code());

        Path infrastructure = root.resolve("order-service-infrastructure/src/main/java/com/example/order/infrastructure/order");
        Path application = root.resolve("order-service-application/src/main/java/com/example/order/application/order");
        Path adapter = root.resolve("order-service-adapter/src/main/java/com/example/order/adapter/order");
        assertTrue(Files.readString(infrastructure.resolve("converter/OrdersPersistenceConverter.java"))
                .startsWith("package com.example.order.infrastructure.order.converter;"));
        assertTrue(Files.readString(infrastructure.resolve("service/impl/OrdersDomainServiceImpl.java"))
                .contains("import com.example.order.infrastructure.order.converter.OrdersPersistenceConverter;"));
        assertTrue(Files.readString(infrastructure.resolve("repo/OrdersRepository.java")).contains("EgonColaRepository"));
        assertTrue(Files.readString(infrastructure.resolve("repo/OrdersRepository.java")).contains("selectByQuery"));
        assertTrue(Files.readString(infrastructure.resolve("service/impl/OrdersDomainServiceImpl.java"))
                .contains("repository.removeById(po)"));
        assertTrue(Files.readString(infrastructure.resolve("service/impl/OrdersDomainServiceImpl.java"))
                .contains("repository.selectByQuery"));
        assertTrue(Files.readString(application.resolve("manage/impl/OrdersManageImpl.java"))
                .contains("updateOrdersCmdExe.execute(command)"));
        assertTrue(Files.readString(application.resolve("executor/command/UpdateOrdersCmdExe.java"))
                .contains("domainService.update(domain)"));
        assertTrue(Files.readString(adapter.resolve("controller/OrdersController.java")).contains("manage.update(command)"));
        assertTrue(Files.exists(adapter.resolve("pojo/dto/CreateOrdersDTO.java")));
        assertTrue(Files.exists(adapter.resolve("pojo/vo/OrdersVO.java")));
        assertTrue(Files.exists(adapter.resolve("pojo/convertor/OrdersAdapterConverter.java")));

        String po = Files.readString(infrastructure.resolve("po/OrdersPO.java"));
        assertTrue(po.contains("extends EgonModel<OrdersPO>"));
        for (String inherited : new String[]{"id", "tenantId", "createUserId", "createTime",
                "updateUserId", "updateTime", "deletedAt", "version"}) {
            assertFalse(po.matches("(?s).*private\\s+\\w+\\s+" + inherited + "\\s*;.*"), inherited);
        }
        assertEquals(0, invoke("check", "--config", config.toString()).code());
    }

    @Test
    void serviceBackendCrudHasNoHttpController(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        String json = Files.readString(config)
                .replace("\"projectType\": \"web\"", "\"projectType\": \"service\"")
                .replace("[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]");
        Files.writeString(config, json);
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        assertEquals(27, mapper.readTree(planned.out()).path("fileCount").asInt());
        assertFalse(mapper.readTree(planned.out()).path("files").toString().contains("Controller.java"));
        String planId = mapper.readTree(planned.out()).path("planId").asText();
        assertEquals(0, invoke("apply", "--config", config.toString(), "--plan", planId).code());
        assertFalse(Files.exists(root.resolve("order-service-adapter/src/main/java/com/example/order/adapter/order/controller/OrdersController.java")));
    }

    @Test
    void selectedCommandsAndQueriesIncludeOnlyTheirMatchingExecutors(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        String original = Files.readString(config);
        for (String artifact : List.of("command", "query")) {
            Files.writeString(config, original.replace("[\"po\", \"dao\", \"mapper-xml\", \"repo\"]",
                    "[\"" + artifact + "\"]"));
            Invocation planned = invoke("plan", "--config", config.toString());
            assertEquals(0, planned.code(), planned.err());
            JsonNode files = mapper.readTree(planned.out()).path("files");
            assertEquals("command".equals(artifact) ? 6 : 4, files.size());
            assertTrue(files.toString().contains("command".equals(artifact) ? "CreateOrdersCmdExe.java" : "OrdersPageQueryExe.java"));
            assertFalse(files.toString().contains("OrdersController.java"));
            assertFalse(files.toString().contains("OrdersRepository.java"));
        }
    }

    @Test
    void legacyLayoutBlocksPlanningAndIsRecheckedBeforeApply(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Files.writeString(config, Files.readString(config).replace(
                "[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]"));
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        String planId = mapper.readTree(planned.out()).path("planId").asText();

        Path legacy = root.resolve("order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "user-owned legacy implementation");
        Invocation checked = invoke("check", "--config", config.toString());
        assertEquals(8, checked.code());
        assertTrue(checked.out().contains("OrdersManageImpl.java"));
        Invocation blocked = invoke("apply", "--config", config.toString(), "--plan", planId);
        assertEquals(4, blocked.code());
        assertTrue(blocked.err().contains("LEGACY_LAYOUT_CONFLICT"));
        assertEquals("user-owned legacy implementation", Files.readString(legacy));
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));
        assertEquals(4, invoke("plan", "--config", config.toString()).code());
    }

    @Test
    void deletedLegacyManagedFileRemainsAConflict(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Files.writeString(config, Files.readString(config).replace(
                "[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]"));
        String oldPath = "order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java";
        GenerationStateRepository state = new GenerationStateRepository();
        state.save(root, GenerationStateRepository.StateBO.builder().formatVersion(1).artifacts(List.of(
                CodegenPlanBO.ArtifactStateBO.builder().path(oldPath).artifact("manage-impl")
                        .logicalTable("orders").previousGeneratedHash("old-hash")
                        .previousGeneratedHashPresent(true).build())).build());
        byte[] before = Files.readAllBytes(GenerationStateRepository.stateFile(root));
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(4, planned.code());
        assertTrue(planned.err().contains("LEGACY_LAYOUT_CONFLICT"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(GenerationStateRepository.stateFile(root))));
    }

    @Test
    void unrelatedLegacyApplicationFileDoesNotBlockPersistenceOnlyScope(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Path legacy = root.resolve("order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "existing application implementation");
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        assertEquals(4, mapper.readTree(planned.out()).path("fileCount").asInt());
        assertEquals("existing application implementation", Files.readString(legacy));
    }

    @Test
    void changingModulePathDoesNotAbandonPreviouslyOwnedFiles(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        String planId = mapper.readTree(planned.out()).path("planId").asText();
        assertEquals(0, invoke("apply", "--config", config.toString(), "--plan", planId).code());
        Path oldPo = root.resolve("order-service-infrastructure/src/main/java/com/example/order/infrastructure/order/po/OrdersPO.java");
        byte[] before = Files.readAllBytes(oldPo);

        ObjectNode document = (ObjectNode) mapper.readTree(config.toFile());
        ((ObjectNode) document.path("modulePaths")).put("infrastructure", "new-infrastructure");
        mapper.writeValue(config.toFile(), document);
        Invocation moved = invoke("plan", "--config", config.toString());
        assertEquals(4, moved.code());
        assertTrue(moved.err().contains("LEGACY_LAYOUT_CONFLICT"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(oldPo)));
        assertFalse(Files.exists(root.resolve("new-infrastructure")));
    }

    @Test
    void reviewedLegacyOwnershipCanBeRetiredOnlyAfterPreservingItsFile(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Files.writeString(config, Files.readString(config).replace(
                "[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]"));
        Invocation originalPlan = invoke("plan", "--config", config.toString());
        assertEquals(0, originalPlan.code(), originalPlan.err());
        String originalPlanId = mapper.readTree(originalPlan.out()).path("planId").asText();
        String oldPath = "order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java";
        String unrelated = "order-service-infrastructure/src/main/java/com/example/order/infrastructure/other/po/OtherPO.java";
        Path oldFile = root.resolve(oldPath);
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "preserve this implementation");
        GenerationStateRepository repository = new GenerationStateRepository();
        repository.save(root, GenerationStateRepository.StateBO.builder().formatVersion(1).artifacts(List.of(
                CodegenPlanBO.ArtifactStateBO.builder().path(oldPath).artifact("manage-impl").logicalTable("orders")
                        .previousGeneratedHash("old-hash").previousGeneratedHashPresent(true).build(),
                CodegenPlanBO.ArtifactStateBO.builder().path(unrelated).artifact("po").logicalTable("other")
                        .previousGeneratedHash("other-hash").previousGeneratedHashPresent(true).build())).build());
        byte[] originalState = Files.readAllBytes(GenerationStateRepository.stateFile(root));
        Invocation check = invoke("check", "--config", config.toString());
        assertEquals(8, check.code());
        JsonNode review = mapper.readTree(check.out()).path("retirementReview");
        assertEquals(1, review.path("paths").size());
        Path reviewFile = root.resolve("reviewed-retirement.json");
        mapper.writeValue(reviewFile.toFile(), review);

        Invocation blocked = invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString());
        assertEquals(4, blocked.code());
        assertTrue(java.util.Arrays.equals(originalState, Files.readAllBytes(GenerationStateRepository.stateFile(root))));
        assertEquals("preserve this implementation", Files.readString(oldFile));

        Path preserved = root.resolve("review-backup/OrdersManageImpl.java");
        Files.createDirectories(preserved.getParent());
        Files.move(oldFile, preserved);
        Invocation retired = invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString());
        assertEquals(0, retired.code(), retired.err());
        assertEquals("preserve this implementation", Files.readString(preserved));
        assertEquals(List.of(unrelated), repository.load(root).getArtifacts().stream().map(CodegenPlanBO.ArtifactStateBO::getPath).toList());
        try (var backups = Files.list(root.resolve(".egon/codegen/state-history"))) {
            Path backup = backups.findFirst().orElseThrow();
            assertTrue(java.util.Arrays.equals(originalState, Files.readAllBytes(backup)));
        }
        assertEquals(6, invoke("apply", "--config", config.toString(), "--plan", originalPlanId).code());
        assertEquals(0, invoke("plan", "--config", config.toString()).code());
    }

    @Test
    void retirementRejectsStaleOrUnreviewedPaths(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Files.writeString(config, Files.readString(config).replace(
                "[\"po\", \"dao\", \"mapper-xml\", \"repo\"]", "[\"backend-crud\"]"));
        String oldPath = "order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java";
        GenerationStateRepository repository = new GenerationStateRepository();
        repository.save(root, GenerationStateRepository.StateBO.builder().formatVersion(1).artifacts(List.of(
                CodegenPlanBO.ArtifactStateBO.builder().path(oldPath).artifact("manage-impl").logicalTable("orders")
                        .previousGeneratedHash("old-hash").previousGeneratedHashPresent(true).build())).build());
        Invocation check = invoke("check", "--config", config.toString());
        ObjectNode review = (ObjectNode) mapper.readTree(check.out()).path("retirementReview");
        Path reviewFile = root.resolve("reviewed-retirement.json");
        ObjectNode altered = review.deepCopy();
        altered.putArray("paths").add("order-service-application/src/main/java/com/example/order/application/order/Unreviewed.java");
        mapper.writeValue(reviewFile.toFile(), altered);
        assertEquals(4, invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString()).code());
        mapper.writeValue(reviewFile.toFile(), review);
        ObjectNode document = (ObjectNode) mapper.readTree(config.toFile());
        ((ObjectNode) document.path("fieldPolicies")).putArray("result");
        mapper.writeValue(config.toFile(), document);
        assertEquals(6, invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString()).code());
        assertEquals(oldPath, repository.load(root).getArtifacts().getFirst().getPath());
    }

    @Test
    void retirementReviewSupportsMultipleSchemaFiles(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        ObjectNode document = (ObjectNode) mapper.readTree(config.toFile());
        document.putArray("artifacts").add("backend-crud");
        Path extraSchema = root.resolve("extra.sql");
        Files.writeString(extraSchema, "CREATE TABLE extra_table (id BIGINT PRIMARY KEY);\n");
        document.withObject("/input").withArray("schemaFiles").add(extraSchema.toString());
        mapper.writeValue(config.toFile(), document);
        String oldPath = "order-service-application/src/main/java/com/example/order/application/order/OrdersManageImpl.java";
        GenerationStateRepository repository = new GenerationStateRepository();
        repository.save(root, GenerationStateRepository.StateBO.builder().formatVersion(1).artifacts(List.of(
                CodegenPlanBO.ArtifactStateBO.builder().path(oldPath).artifact("manage-impl").logicalTable("orders")
                        .previousGeneratedHash("old-hash").previousGeneratedHashPresent(true).build())).build());
        Invocation check = invoke("check", "--config", config.toString());
        assertEquals(8, check.code(), check.err());
        JsonNode review = mapper.readTree(check.out()).path("retirementReview");
        assertTrue(review.path("inputFingerprint").asText().contains("\n"));
        Path reviewFile = root.resolve("reviewed-retirement.json");
        mapper.writeValue(reviewFile.toFile(), review);
        Invocation retired = invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString());
        assertEquals(0, retired.code(), retired.err());
        assertTrue(repository.load(root).getArtifacts().isEmpty());
    }

    @Test
    void enabledEventsRequireExplicitDefinitions(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Files.writeString(config, Files.readString(config).replace("\"enabled\": false", "\"enabled\": true"));
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(2, planned.code());
        assertTrue(planned.err().contains("CONFIG_REQUIRED"));
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));
    }

    @Test
    void explicitInboundEventCreatesItsModelConverterAndExecutor(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        ObjectNode document = (ObjectNode) mapper.readTree(config.toFile());
        document.putArray("artifacts").add("backend-crud");
        ObjectNode events = document.putObject("events");
        events.put("enabled", true);
        ObjectNode event = events.putArray("definitions").addObject();
        event.put("table", "orders");
        event.put("name", "OrderImportedEvent");
        event.put("action", "create");
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.order.support.EventDedupService");
        event.put("idempotencyBean", "eventDedupService");
        event.put("idempotencyMethod", "executeOnce");
        event.putObject("fieldMappings").put("code", "externalCode");
        ObjectNode update = events.withArray("definitions").addObject();
        update.put("table", "orders");
        update.put("name", "OrderUpdatedEvent");
        update.put("action", "update");
        update.put("schemaVersion", "v1");
        update.put("idempotencyType", "com.example.order.support.EventDedupService");
        update.put("idempotencyBean", "eventDedupService");
        update.put("idempotencyMethod", "executeOnce");
        update.putObject("fieldMappings").put("id", "orderId").put("expectedVersion", "version")
                .put("code", "externalCode");
        ObjectNode deletion = events.withArray("definitions").addObject();
        deletion.put("table", "orders");
        deletion.put("name", "OrderRemovedEvent");
        deletion.put("action", "delete");
        deletion.put("schemaVersion", "v2");
        deletion.put("idempotencyType", "com.example.order.support.EventDedupService");
        deletion.put("idempotencyBean", "eventDedupService");
        deletion.put("idempotencyMethod", "executeOnce");
        deletion.putObject("fieldMappings").put("id", "orderId").put("expectedVersion", "version");
        mapper.writeValue(config.toFile(), document);

        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        JsonNode plan = mapper.readTree(planned.out());
        assertEquals(45, plan.path("fileCount").asInt());
        assertTrue(plan.path("files").toString().contains("OrderImportedEventExe.java"));
        assertTrue(plan.path("files").toString().contains("OrderUpdatedEventExe.java"));
        assertTrue(plan.path("files").toString().contains("OrderRemovedEventExe.java"));
        assertEquals(0, invoke("apply", "--config", config.toString(), "--plan", plan.path("planId").asText()).code());
        Path app = root.resolve("order-service-application/src/main/java/com/example/order/application/order");
        String executor = Files.readString(app.resolve("executor/event/OrderImportedEventExe.java"));
        assertTrue(executor.contains("idempotency.executeOnce(\"OrderImportedEvent\", event.getEventId(), event"));
        assertTrue(executor.contains("commandExecutor.execute(converter.toTarget(event))"));
        assertTrue(executor.contains("\"v1\".equals(event.getSchemaVersion())"));
        assertFalse(executor.contains("RabbitTemplate"));
        assertTrue(Files.readString(app.resolve("pojo/convertor/OrderImportedEventConverter.java"))
                .contains("@Mapping(target = \"code\", source = \"externalCode\")"));
        assertTrue(Files.readString(app.resolve("pojo/convertor/OrderUpdatedEventConverter.java"))
                .contains("@Mapping(target = \"expectedVersion\", source = \"version\")"));
        assertTrue(Files.readString(app.resolve("executor/event/OrderRemovedEventExe.java"))
                .contains("deleteOrdersCmdExe"));
        String handler = Files.readString(root.resolve("order-service-adapter/src/main/java/com/example/order/adapter/order/event/OrderImportedEventHandler.java"));
        assertTrue(handler.contains("orderImportedEventExe.execute(event)"));
        assertFalse(handler.contains("@RabbitListener"));
        assertEquals(0, invoke("check", "--config", config.toString()).code());

        Invocation unchangedPlan = invoke("plan", "--config", config.toString());
        assertEquals(0, unchangedPlan.code());
        String stalePlan = mapper.readTree(unchangedPlan.out()).path("planId").asText();
        event.put("schemaVersion", "v2");
        mapper.writeValue(config.toFile(), document);
        assertEquals(6, invoke("apply", "--config", config.toString(), "--plan", stalePlan).code());

        events.put("enabled", false);
        events.remove("definitions");
        mapper.writeValue(config.toFile(), document);
        Invocation omitted = invoke("plan", "--config", config.toString());
        assertEquals(4, omitted.code());
        assertTrue(omitted.err().contains("EVENT_SCOPE_CONFLICT"));
        Invocation drift = invoke("check", "--config", config.toString());
        assertEquals(8, drift.code());
        assertTrue(drift.out().contains("OrderImportedEventExe.java"));
        assertTrue(Files.exists(app.resolve("executor/event/OrderImportedEventExe.java")));

        document.putArray("artifacts").add("persistence-crud");
        mapper.writeValue(config.toFile(), document);
        assertEquals(0, invoke("plan", "--config", config.toString()).code());

        document.putArray("artifacts").add("backend-crud");
        mapper.writeValue(config.toFile(), document);
        JsonNode review = mapper.readTree(invoke("check", "--config", config.toString()).out()).path("retirementReview");
        assertEquals(12, review.path("paths").size());
        Path reviewFile = root.resolve("reviewed-events.json");
        mapper.writeValue(reviewFile.toFile(), review);
        assertEquals(4, invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString()).code());
        for (JsonNode path : review.path("paths")) {
            Path source = root.resolve(path.asText());
            Path preserved = root.resolve("event-backup").resolve(path.asText());
            Files.createDirectories(preserved.getParent());
            Files.move(source, preserved);
        }
        Invocation retired = invoke("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString());
        assertEquals(0, retired.code(), retired.err());
        assertTrue(Files.exists(root.resolve("event-backup").resolve(
                "order-service-application/src/main/java/com/example/order/application/order/executor/event/OrderImportedEventExe.java")));
        assertEquals(0, invoke("plan", "--config", config.toString()).code());
    }

    @Test
    void invalidEventMappingsAndGuardAreRejectedBeforePlanning(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        ObjectNode document = (ObjectNode) mapper.readTree(config.toFile());
        document.putArray("artifacts").add("backend-crud");
        ObjectNode event = document.putObject("events").put("enabled", true).putArray("definitions").addObject();
        event.put("table", "orders");
        event.put("name", "OrderImportedEvent");
        event.put("action", "create");
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.order.support.EventDedupService");
        event.put("idempotencyBean", "eventDedupService");
        event.put("idempotencyMethod", "executeOnce");
        event.putObject("fieldMappings").put("code", "eventId");
        mapper.writeValue(config.toFile(), document);
        Invocation reserved = invoke("plan", "--config", config.toString());
        assertEquals(3, reserved.code());
        assertTrue(reserved.err().contains("event source fields"));

        event.putObject("fieldMappings").put("wrongField", "externalCode");
        mapper.writeValue(config.toFile(), document);
        assertEquals(3, invoke("plan", "--config", config.toString()).code());

        event.putObject("fieldMappings").put("code", "externalCode");
        event.put("idempotencyBean", "bad\"bean");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, invoke("plan", "--config", config.toString()).code());
        event.put("idempotencyBean", "eventDedupService");
        event.put("idempotencyType", "com.example.Guard;Injected");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, invoke("plan", "--config", config.toString()).code());
        event.put("idempotencyType", "com.example.order.support.EventDedupService");
        event.put("schemaVersion", "v1\"invalid");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, invoke("plan", "--config", config.toString()).code());
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.order.infrastructure.order.EventDedupServiceImpl");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, invoke("plan", "--config", config.toString()).code());
        event.put("idempotencyType", "com.example.order.support.OrderImportedEventExe");
        mapper.writeValue(config.toFile(), document);
        assertEquals(3, invoke("plan", "--config", config.toString()).code());
        event.put("idempotencyType", "com.example.order.support.EventDedupService");
        ((ObjectNode) document.path("modulePaths")).remove("adapter");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, invoke("plan", "--config", config.toString()).code());
        ((ObjectNode) document.path("modulePaths")).put("adapter", "order-service-adapter");
        ((ObjectNode) document.path("fieldPolicies")).putArray("create").add("unknown_column");
        event.putObject("fieldMappings");
        mapper.writeValue(config.toFile(), document);
        Invocation unknown = invoke("plan", "--config", config.toString());
        assertEquals(3, unknown.code());
        assertTrue(unknown.err().contains("existing business columns"));
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));
    }

    @Test
    void oldPlanRejectsChangedConfigurationAndDdl(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Path schema = root.resolve("schema.sql");
        Files.copy(Path.of("src/test/resources/ddl/schema.sql"), schema);
        String original = Files.readString(config).replace(
                Path.of("src/test/resources/ddl/schema.sql").toAbsolutePath().toString(), schema.toString());
        Files.writeString(config, original);
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        String planId = mapper.readTree(planned.out()).path("planId").asText();

        Files.writeString(config, original.replace("\"result\": [\"code\"]", "\"result\": []"));
        Invocation changedConfig = invoke("apply", "--config", config.toString(), "--plan", planId);
        assertEquals(6, changedConfig.code(), changedConfig.err());
        Files.writeString(config, original);

        Files.writeString(schema, Files.readString(schema) + "\nCREATE TABLE schema_marker (id BIGINT PRIMARY KEY);\n");
        Invocation changedDdl = invoke("apply", "--config", config.toString(), "--plan", planId);
        assertEquals(6, changedDdl.code(), changedDdl.err());
    }

    @Test
    void oldFormatAndEditedPlanCannotApply(@TempDir Path root) throws Exception {
        Path config = writeConfig(root);
        Invocation planned = invoke("plan", "--config", config.toString());
        assertEquals(0, planned.code(), planned.err());
        String planId = mapper.readTree(planned.out()).path("planId").asText();
        Path planFile = root.resolve(".egon/codegen/plans/" + planId + ".json");
        ObjectNode document = (ObjectNode) mapper.readTree(planFile.toFile());
        document.put("formatVersion", 1);
        mapper.writeValue(planFile.toFile(), document);
        assertEquals(6, invoke("apply", "--config", config.toString(), "--plan", planId).code());
        document.put("formatVersion", 2);
        ((ObjectNode) document.path("files").get(0)).put("operation", "UPDATE");
        mapper.writeValue(planFile.toFile(), document);
        assertEquals(6, invoke("apply", "--config", config.toString(), "--plan", planId).code());
        assertFalse(Files.exists(root.resolve("order-service-infrastructure")));
    }

    private Path writeConfig(Path root) throws Exception {
        String json = Files.readString(Path.of("src/test/resources/codegen-web.json"))
                .replace("target/codegen-web-example", root.toString())
                .replace("src/test/resources/ddl/schema.sql",
                        Path.of("src/test/resources/ddl/schema.sql").toAbsolutePath().toString());
        Path config = root.resolve("codegen.json");
        Files.writeString(config, json);
        return config;
    }

    private Invocation invoke(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = command.run(args, new PrintStream(output), new PrintStream(error));
        return new Invocation(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int code, String out, String err) {
    }
}
