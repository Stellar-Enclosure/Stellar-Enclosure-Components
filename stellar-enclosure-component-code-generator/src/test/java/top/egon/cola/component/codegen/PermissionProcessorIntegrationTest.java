package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.codegen.cli.CodegenCommand;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in integration with the target project's real Permission-as-Code processor and dependencies. */
class PermissionProcessorIntegrationTest {

    @Test
    void generatedResourcesCompileIntoRealPermissionFragment(@TempDir Path root) throws Exception {
        String processorClasspath = System.getProperty("codegen.permissionProcessorClasspath", "");
        Assumptions.assumeTrue(!processorClasspath.isBlank(),
                "set codegen.permissionProcessorClasspath to the real processor and target runtime classpath");
        ObjectMapper mapper = new ObjectMapper();
        Path schema = root.resolve("schema.sql");
        Files.writeString(schema, Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64), amount DECIMAL(12,2), due_date DATE"));
        ObjectNode document = (ObjectNode) mapper.readTree(CodegenAcceptanceTest.config("light", root));
        document.withObject("/input").putArray("schemaFiles").add(schema.toString());
        for (String policy : List.of("create", "update", "result", "filter")) {
            document.withObject("/fieldPolicies").putArray(policy).add("code").add("amount").add("due_date");
        }
        TestContracts.addJson(document, "orders", "Orders", "/api/orders", "code", "amount", "dueDate");
        var references = document.withObject("/apiContract").withArray("permissionReferences");
        for (int index = references.size() - 1; index >= 0; index--) {
            if ("fixture:orders:read".equals(references.get(index).asText())) references.remove(index);
        }
        document.withObject("/apiContract").putArray("localPermissions").add("fixture:orders:read");
        Path config = root.resolve("codegen.json");
        mapper.writeValue(config.toFile(), document);
        CodegenCommand command = new CodegenCommand();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, command.run(new String[]{"plan", "--config", config.toString()},
                new PrintStream(output), new PrintStream(errors)), errors.toString(StandardCharsets.UTF_8));
        String plan = mapper.readTree(output.toString(StandardCharsets.UTF_8)).path("planId").asText();
        output.reset(); errors.reset();
        assertEquals(0, command.run(new String[]{"apply", "--config", config.toString(), "--plan", plan},
                new PrintStream(output), new PrintStream(errors)), errors.toString(StandardCharsets.UTF_8));
        Path errorMapper = root.resolve("src/main/java/com/example/codegenfixture/support/FixtureErrorMapper.java");
        Files.createDirectories(errorMapper.getParent());
        Files.writeString(errorMapper, """
                package com.example.codegenfixture.support;
                public final class FixtureErrorMapper {
                    public static RuntimeException missing(Long id) { return new IllegalStateException(); }
                    public static RuntimeException zeroRows(String operation, Long id, Long version) { return new IllegalStateException(); }
                }
                """);
        Path localPermission = root.resolve("src/main/java/com/example/codegenfixture/support/FixturePermissionDefinition.java");
        Files.writeString(localPermission, """
                package com.example.codegenfixture.support;
                import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionDefinition;
                @PermissionDefinition(code = "fixture:orders:read", meaning = "Read order fields",
                        labelPage = "fixture.orders", labelKey = "permission.read")
                public final class FixturePermissionDefinition { }
                """);
        Path classes = root.resolve("compiled");
        Files.createDirectories(classes);
        Path repository = Path.of(System.getProperty("maven.repo.local", Path.of(System.getProperty("user.home"), ".m2/repository").toString()));
        Path lombok = repository.resolve("org/projectlombok/lombok/1.18.46/lombok-1.18.46.jar");
        Path mapstruct = repository.resolve("org/mapstruct/mapstruct-processor/1.6.3/mapstruct-processor-1.6.3.jar");
        List<Path> files;
        try (var paths = Files.walk(root.resolve("src/main/java"))) {
            files = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        String separator = java.io.File.pathSeparator;
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-proc:full", "-classpath",
                System.getProperty("java.class.path") + separator + lombok + separator + processorClasspath,
                "-processorpath", lombok + separator + mapstruct + separator + processorClasspath,
                "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor,org.mapstruct.ap.MappingProcessor,"
                + "top.egon.cola.platform.tianquan.jianshen.processor.PermissionManifestProcessor",
                "-Apac.business=fixture", "-Apac.application=orders", "-Apac.producer=orders-java", "-Apac.module=app",
                "-Apac.expectedModules=app", "-Apac.ownedKeyPrefixes=fixture.",
                "-Apac.managedPackages=com.example.codegenfixture.adapter", "-Apac.repository=fixture-repo",
                "-Apac.commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "-Apac.buildId=test-build", "-Apac.version=1"));
        files.forEach(file -> args.add(file.toString()));
        ByteArrayOutputStream compilation = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, compilation, compilation, args.toArray(String[]::new)),
                compilation.toString(StandardCharsets.UTF_8));
        JsonNode fragment = mapper.readTree(classes.resolve("META-INF/permissions/fragment-v1.json").toFile());
        assertEquals(18, fragment.path("resources").size());
        JsonNode resultDate = null;
        JsonNode updateCode = null;
        JsonNode page = null;
        for (JsonNode resource : fragment.path("resources")) {
            if ("fixture.field.orders.result.dueDate".equals(resource.path("key").asText())) resultDate = resource;
            if ("fixture.field.orders.update.code".equals(resource.path("key").asText())) updateCode = resource;
            if ("fixture.api.orders.page".equals(resource.path("key").asText())) page = resource;
        }
        assertTrue(resultDate != null && updateCode != null && page != null);
        assertEquals("STRING", resultDate.path("fieldPolicy").path("valueType").asText());
        assertEquals("fixture:orders:read", resultDate.path("fieldPolicy").path("plainRead").path("codes").get(0).asText());
        assertEquals("fixture:orders:field:write", updateCode.path("fieldPolicy").path("write").path("codes").get(0).asText());
        assertEquals(java.util.Set.of("fixture:orders:page", "fixture:orders:read"),
                mapper.convertValue(page.path("requirement").path("codes"),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() { }));
        assertEquals(6, fragment.path("permissionReferences").size());
        assertEquals("fixture:orders:read", fragment.path("permissionDefinitions").get(0).path("code").asText());
    }
}
