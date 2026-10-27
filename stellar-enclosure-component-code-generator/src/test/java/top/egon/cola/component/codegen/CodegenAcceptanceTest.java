package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import top.egon.cola.component.codegen.cli.CodegenCommand;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodegenAcceptanceTest {

    private final CodegenCommand command = new CodegenCommand();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void threeLayoutsCompileAndDoNotEmbedTheTemplateEngine(@TempDir Path root) throws Exception {
        for (String profile : List.of("light", "web", "service")) {
            Path project = root.resolve(profile);
            Files.createDirectories(project);
            Path config = project.resolve("codegen.json");
            Files.writeString(config, config(profile, project));
            assertEquals(0, run("plan", "--config", config.toString()));
            String planId = mapper.readTree(lastOut).get("planId").asText();
            assertEquals(0, run("apply", "--config", config.toString(), "--plan", planId), lastErr);
            Path javaRoot = project.resolve(profile.equals("light") ? "" : "stellar-enclosure-source-" + profile + "-infrastructure");
            Path po = javaRoot.resolve("src/main/java/com/example/codegenfixture/infrastructure/codegenfixture/po/OrdersPO.java");
            Path repository = javaRoot.resolve("src/main/java/com/example/codegenfixture/infrastructure/codegenfixture/repo/OrdersRepository.java");
            assertTrue(Files.exists(po));
            String poSource = Files.readString(po);
            String repositorySource = Files.readString(repository);
            assertFalse(poSource.contains("freemarker"));
            assertFalse(repositorySource.contains("freemarker"));
            assertTrue(poSource.contains("package com.example.codegenfixture.infrastructure.codegenfixture.po"));
            assertTrue(repositorySource.contains("extends EgonColaRepository"));
            Path support = javaRoot.resolve("src/main/java/com/example/codegenfixture/support/FixtureErrorMapper.java");
            Files.createDirectories(support.getParent());
            Files.writeString(support, """
                    package com.example.codegenfixture.support;
                    public final class FixtureErrorMapper {
                        public static RuntimeException missing(Long id) { return new IllegalStateException("missing:" + id); }
                        public static RuntimeException zeroRows(String operation, Long id, Long version) {
                            return new IllegalStateException(operation + ":" + id + ":" + version);
                        }
                    }
                    """);
            compile(project, project.resolve("classes"));
            String converter = "com/example/codegenfixture/infrastructure/codegenfixture/converter/OrdersPersistenceConverter";
            assertTrue(Files.exists(project.resolve("classes/" + converter + ".class")));
            assertTrue(Files.exists(project.resolve("classes/" + converter + "Impl.class")));
            assertTrue(Files.exists(project.resolve("annotations/" + converter + "Impl.java")));
            assertFalse(Files.exists(project.resolve("classes/com/example/codegenfixture/infrastructure/codegenfixture/service/impl/OrdersPersistenceConverterImpl.class")));
            assertEquals(0, run("check", "--config", config.toString()), lastErr);
        }
        String launcher = Files.readString(Path.of("src/main/java/top/egon/cola/component/codegen/cli/CodegenCommand.java"));
        assertFalse(launcher.contains("SpringApplication"));
        assertFalse(launcher.contains("DriverManager"));
    }

    @Test
    void emptyCreateAndUpdateFieldPoliciesStillCompile(@TempDir Path root) throws Exception {
        Path config = root.resolve("codegen.json");
        Files.writeString(config, config("web", root)
                .replace("\"create\": [\"code\"]", "\"create\": []")
                .replace("\"update\": [\"code\"]", "\"update\": []"));
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
        String planId = mapper.readTree(lastOut).get("planId").asText();
        assertEquals(0, run("apply", "--config", config.toString(), "--plan", planId), lastErr);
        Path support = root.resolve("stellar-enclosure-source-web-infrastructure/src/main/java/com/example/codegenfixture/support/FixtureErrorMapper.java");
        Files.createDirectories(support.getParent());
        Files.writeString(support, """
                package com.example.codegenfixture.support;
                public final class FixtureErrorMapper {
                    public static RuntimeException missing(Long id) { return new IllegalStateException(); }
                    public static RuntimeException zeroRows(String operation, Long id, Long version) {
                        return new IllegalStateException();
                    }
                }
                """);
        compile(root, root.resolve("classes"));
        String converter = Files.readString(root.resolve(
                "stellar-enclosure-source-web-application/src/main/java/com/example/codegenfixture/application/codegenfixture/pojo/convertor/CreateOrdersCommandConverter.java"));
        assertTrue(converter.contains("@Mapping(target = \"code\", ignore = true)"));
    }

    @Test
    void numericAndTemporalFieldsCompileAcrossCrudAndInboundEvent(@TempDir Path root) throws Exception {
        Path schema = root.resolve("schema.sql");
        Files.writeString(schema, Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64), amount DECIMAL(12,2), due_date DATE"));
        Path config = root.resolve("codegen.json");
        ObjectNode document = (ObjectNode) mapper.readTree(config("web", root));
        document.withObject("/input").putArray("schemaFiles").add(schema.toString());
        ObjectNode policies = document.withObject("/fieldPolicies");
        for (String policy : List.of("create", "update", "result", "filter")) {
            policies.putArray(policy).add("code").add("amount").add("due_date");
        }
        ObjectNode event = document.putObject("events").put("enabled", true).putArray("definitions").addObject();
        event.put("table", "orders");
        event.put("name", "OrderImportedEvent");
        event.put("action", "create");
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.codegenfixture.support.FixtureEventGuard");
        event.put("idempotencyBean", "fixtureEventGuard");
        event.put("idempotencyMethod", "executeOnce");
        event.putObject("fieldMappings").put("code", "externalCode")
                .put("amount", "externalAmount").put("dueDate", "externalDueDate");
        mapper.writeValue(config.toFile(), document);
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
        String planId = mapper.readTree(lastOut).get("planId").asText();
        assertEquals(0, run("apply", "--config", config.toString(), "--plan", planId), lastErr);
        Path support = root.resolve("stellar-enclosure-source-web-application/src/main/java/com/example/codegenfixture/support");
        Files.createDirectories(support);
        Files.writeString(support.resolve("FixtureEventGuard.java"), """
                package com.example.codegenfixture.support;
                public class FixtureEventGuard {
                    public void executeOnce(String type, String id, Object event, Runnable work) { work.run(); }
                }
                """);
        Files.writeString(support.resolve("FixtureErrorMapper.java"), """
                package com.example.codegenfixture.support;
                public final class FixtureErrorMapper {
                    public static RuntimeException missing(Long id) { return new IllegalStateException(); }
                    public static RuntimeException zeroRows(String operation, Long id, Long version) {
                        return new IllegalStateException();
                    }
                }
                """);
        compile(root, root.resolve("classes"));
        String eventSource = Files.readString(root.resolve("stellar-enclosure-source-web-application/src/main/java/"
                + "com/example/codegenfixture/application/codegenfixture/pojo/event/OrderImportedEvent.java"));
        assertTrue(eventSource.contains("java.math.BigDecimal externalAmount"));
        assertTrue(eventSource.contains("java.time.LocalDate externalDueDate"));
    }

    @Test
    void configuredInboundEventMapsAndUsesTheExistingIdempotencyGuard(@TempDir Path root) throws Exception {
        Path config = root.resolve("codegen.json");
        ObjectNode document = (ObjectNode) mapper.readTree(config("web", root));
        var definitions = document.putObject("events").put("enabled", true).putArray("definitions");
        ObjectNode event = definitions.addObject();
        event.put("table", "orders");
        event.put("name", "OrderImportedEvent");
        event.put("action", "create");
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.codegenfixture.support.FixtureEventGuard");
        event.put("idempotencyBean", "fixtureEventGuard");
        event.put("idempotencyMethod", "executeOnce");
        event.putObject("fieldMappings").put("code", "externalCode");
        ObjectNode update = definitions.addObject();
        update.put("table", "orders");
        update.put("name", "OrderUpdatedEvent");
        update.put("action", "update");
        update.put("schemaVersion", "v1");
        update.put("idempotencyType", "com.example.codegenfixture.support.FixtureEventGuard");
        update.put("idempotencyBean", "fixtureEventGuard");
        update.put("idempotencyMethod", "executeOnce");
        update.putObject("fieldMappings").put("id", "orderId").put("expectedVersion", "version")
                .put("code", "externalCode");
        ObjectNode deletion = definitions.addObject();
        deletion.put("table", "orders");
        deletion.put("name", "OrderRemovedEvent");
        deletion.put("action", "delete");
        deletion.put("schemaVersion", "v2");
        deletion.put("idempotencyType", "com.example.codegenfixture.support.FixtureEventGuard");
        deletion.put("idempotencyBean", "fixtureEventGuard");
        deletion.put("idempotencyMethod", "executeOnce");
        deletion.putObject("fieldMappings").put("id", "orderId").put("expectedVersion", "version");
        mapper.writeValue(config.toFile(), document);
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
        String planId = mapper.readTree(lastOut).get("planId").asText();
        assertEquals(0, run("apply", "--config", config.toString(), "--plan", planId), lastErr);

        Path javaRoot = root.resolve("stellar-enclosure-source-web-application/src/main/java");
        Path guard = javaRoot.resolve("com/example/codegenfixture/support/FixtureEventGuard.java");
        Files.createDirectories(guard.getParent());
        Files.writeString(guard, """
                package com.example.codegenfixture.support;
                public class FixtureEventGuard {
                    private final java.util.Map<String, Integer> completed = new java.util.HashMap<>();
                    public void executeOnce(String type, String id, Object event, Runnable work) {
                        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            throw new IllegalStateException("missing transaction");
                        String key = type + ":" + id;
                        Integer previous = completed.get(key);
                        if (previous != null) {
                            if (previous != event.hashCode()) throw new IllegalArgumentException("conflicting event");
                            return;
                        }
                        work.run();
                        completed.put(key, event.hashCode());
                    }
                }
                """);
        Path fake = javaRoot.resolve("com/example/codegenfixture/domain/codegenfixture/service/FakeOrdersDomainService.java");
        Files.createDirectories(fake.getParent());
        Files.writeString(fake, """
                package com.example.codegenfixture.domain.codegenfixture.service;
                import com.example.codegenfixture.domain.codegenfixture.vos.OrdersBO;
                import com.example.codegenfixture.domain.codegenfixture.vos.OrdersDomainQuery;
                import top.egon.cola.component.common.core.pojo.PageSlice;
                public class FakeOrdersDomainService implements OrdersDomainService {
                    public int created;
                    public boolean fail;
                    public Long updatedVersion;
                    public Long deletedVersion;
                    public OrdersBO create(OrdersBO value) {
                        requireTransaction();
                        if (fail) throw new IllegalStateException("retry");
                        created++;
                        return value.setId(1L);
                    }
                    public OrdersBO update(OrdersBO value) { requireTransaction(); updatedVersion = value.getVersion(); return value; }
                    public void delete(Long id, Long version) { requireTransaction(); deletedVersion = version; }
                    public OrdersBO detail(Long id) { return new OrdersBO().setId(id); }
                    public PageSlice<OrdersBO> query(OrdersDomainQuery query) { return PageSlice.of(java.util.List.of(), false); }
                    private static void requireTransaction() {
                        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            throw new IllegalStateException("missing transaction");
                    }
                }
                """);
        Path errorMapper = javaRoot.resolve("com/example/codegenfixture/support/FixtureErrorMapper.java");
        Files.writeString(errorMapper, """
                package com.example.codegenfixture.support;
                public final class FixtureErrorMapper {
                    public static RuntimeException missing(Long id) { return new IllegalStateException(); }
                    public static RuntimeException zeroRows(String operation, Long id, Long version) {
                        return new IllegalStateException();
                    }
                }
                """);
        Path classes = root.resolve("classes");
        compile(root, classes);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            String app = "com.example.codegenfixture.application.codegenfixture.";
            Object domain = loader.loadClass("com.example.codegenfixture.domain.codegenfixture.service.FakeOrdersDomainService")
                    .getConstructor().newInstance();
            Object commandConverter = loader.loadClass(app + "pojo.convertor.CreateOrdersCommandConverterImpl").getConstructor().newInstance();
            Object resultConverter = loader.loadClass(app + "pojo.convertor.OrdersResultConverterImpl").getConstructor().newInstance();
            Object commandExe = loader.loadClass(app + "executor.command.CreateOrdersCmdExe")
                    .getDeclaredConstructors()[0].newInstance(domain, commandConverter, resultConverter);
            Object eventConverter = loader.loadClass(app + "pojo.convertor.OrderImportedEventConverterImpl").getConstructor().newInstance();
            Object dedup = loader.loadClass("com.example.codegenfixture.support.FixtureEventGuard").getConstructor().newInstance();
            RecordingTransactionManager transactions = new RecordingTransactionManager();
            Class<?> eventExeType = loader.loadClass(app + "executor.event.OrderImportedEventExe");
            assertTrue(eventExeType.getMethod("execute", loader.loadClass(app + "pojo.event.OrderImportedEvent"))
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
            Object eventExe = transactional(eventExeType.getDeclaredConstructors()[0]
                    .newInstance(dedup, commandExe, eventConverter), transactions, loader);
            Class<?> eventType = loader.loadClass(app + "pojo.event.OrderImportedEvent");
            Class<?> handlerType = loader.loadClass("com.example.codegenfixture.adapter.codegenfixture.event.OrderImportedEventHandler");
            Object handler = handlerType.getDeclaredConstructors()[0].newInstance(eventExe);
            Object payload = eventType.getConstructor().newInstance();
            eventType.getMethod("setEventId", String.class).invoke(payload, "event-1");
            eventType.getMethod("setSchemaVersion", String.class).invoke(payload, "v1");
            eventType.getMethod("setExternalCode", String.class).invoke(payload, "order-1");
            handlerType.getMethod("handle", eventType).invoke(handler, payload);
            handlerType.getMethod("handle", eventType).invoke(handler, payload);
            assertEquals(1, domain.getClass().getField("created").get(domain));
            eventType.getMethod("setExternalCode", String.class).invoke(payload, "conflicting-order");
            assertThrows(InvocationTargetException.class, () -> eventExeType.getMethod("execute", eventType).invoke(eventExe, payload));
            assertEquals(1, domain.getClass().getField("created").get(domain));
            eventType.getMethod("setExternalCode", String.class).invoke(payload, "order-1");

            eventType.getMethod("setEventId", String.class).invoke(payload, "event-2");
            eventType.getMethod("setSchemaVersion", String.class).invoke(payload, "v2");
            assertThrows(InvocationTargetException.class, () -> eventExeType.getMethod("execute", eventType).invoke(eventExe, payload));
            assertEquals(1, domain.getClass().getField("created").get(domain));
            eventType.getMethod("setSchemaVersion", String.class).invoke(payload, "v1");
            domain.getClass().getField("fail").set(domain, true);
            assertThrows(InvocationTargetException.class, () -> eventExeType.getMethod("execute", eventType).invoke(eventExe, payload));
            domain.getClass().getField("fail").set(domain, false);
            eventExeType.getMethod("execute", eventType).invoke(eventExe, payload);
            assertEquals(2, domain.getClass().getField("created").get(domain));

            Object updateConverter = loader.loadClass(app + "pojo.convertor.UpdateOrdersCommandConverterImpl").getConstructor().newInstance();
            Object updateCommand = loader.loadClass(app + "executor.command.UpdateOrdersCmdExe")
                    .getDeclaredConstructors()[0].newInstance(domain, updateConverter, resultConverter);
            Object updateEventConverter = loader.loadClass(app + "pojo.convertor.OrderUpdatedEventConverterImpl").getConstructor().newInstance();
            Class<?> updateExeType = loader.loadClass(app + "executor.event.OrderUpdatedEventExe");
            Object updateExe = transactional(updateExeType.getDeclaredConstructors()[0]
                    .newInstance(dedup, updateCommand, updateEventConverter), transactions, loader);
            Class<?> updateEventType = loader.loadClass(app + "pojo.event.OrderUpdatedEvent");
            Object updatePayload = updateEventType.getConstructor().newInstance();
            updateEventType.getMethod("setEventId", String.class).invoke(updatePayload, "event-update");
            updateEventType.getMethod("setSchemaVersion", String.class).invoke(updatePayload, "v1");
            updateEventType.getMethod("setOrderId", Long.class).invoke(updatePayload, 1L);
            updateEventType.getMethod("setVersion", Long.class).invoke(updatePayload, 7L);
            updateEventType.getMethod("setExternalCode", String.class).invoke(updatePayload, "order-2");
            updateExeType.getMethod("execute", updateEventType).invoke(updateExe, updatePayload);
            assertEquals(7L, domain.getClass().getField("updatedVersion").get(domain));

            Object deleteConverter = loader.loadClass(app + "pojo.convertor.DeleteOrdersCommandConverterImpl").getConstructor().newInstance();
            Object deleteCommand = loader.loadClass(app + "executor.command.DeleteOrdersCmdExe")
                    .getDeclaredConstructors()[0].newInstance(domain, deleteConverter);
            Object deleteEventConverter = loader.loadClass(app + "pojo.convertor.OrderRemovedEventConverterImpl").getConstructor().newInstance();
            Class<?> deleteExeType = loader.loadClass(app + "executor.event.OrderRemovedEventExe");
            Object deleteExe = transactional(deleteExeType.getDeclaredConstructors()[0]
                    .newInstance(dedup, deleteCommand, deleteEventConverter), transactions, loader);
            Class<?> deleteEventType = loader.loadClass(app + "pojo.event.OrderRemovedEvent");
            Object deletePayload = deleteEventType.getConstructor().newInstance();
            deleteEventType.getMethod("setEventId", String.class).invoke(deletePayload, "event-delete");
            deleteEventType.getMethod("setSchemaVersion", String.class).invoke(deletePayload, "v2");
            deleteEventType.getMethod("setOrderId", Long.class).invoke(deletePayload, 1L);
            deleteEventType.getMethod("setVersion", Long.class).invoke(deletePayload, 8L);
            deleteExeType.getMethod("execute", deleteEventType).invoke(deleteExe, deletePayload);
            assertEquals(8L, domain.getClass().getField("deletedVersion").get(domain));
            assertEquals(3, transactions.rolledBack);
        }
    }

    private static Object transactional(Object target, RecordingTransactionManager manager, ClassLoader loader) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return factory.getProxy(loader);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private Object active;
        private int rolledBack;

        @Override
        protected Object doGetTransaction() {
            return active == null ? new Object() : active;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active != null && active == transaction;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = transaction;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active = null;
        }
    }

    private String lastOut = "";

    private String lastErr = "";

    private int run(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = command.run(args, new PrintStream(output), new PrintStream(error));
        lastOut = output.toString(StandardCharsets.UTF_8);
        lastErr = error.toString(StandardCharsets.UTF_8);
        return code;
    }

    private static void compile(Path sourceRoot, Path classes) throws Exception {
        Files.createDirectories(classes);
        List<Path> files;
        try (var paths = Files.walk(sourceRoot)) {
            files = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> !sourceRoot.relativize(path).startsWith(".egon")).toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path lombok = jar("org/projectlombok/lombok/1.18.46/lombok-1.18.46.jar");
        List<String> arguments = new ArrayList<>();
        arguments.add("-classpath");
        arguments.add(System.getProperty("java.class.path") + java.io.File.pathSeparator + lombok
                + java.io.File.pathSeparator + jar("org/mapstruct/mapstruct/1.6.3/mapstruct-1.6.3.jar")
                + java.io.File.pathSeparator + jar("org/springframework/spring-web/"
                + org.springframework.core.SpringVersion.getVersion() + "/spring-web-"
                + org.springframework.core.SpringVersion.getVersion() + ".jar"));
        arguments.add("-processorpath");
        arguments.add(lombok + java.io.File.pathSeparator
                + jar("org/mapstruct/mapstruct-processor/1.6.3/mapstruct-processor-1.6.3.jar"));
        arguments.add("-s");
        arguments.add(sourceRoot.resolve("annotations").toString());
        arguments.add("-d");
        arguments.add(classes.toString());
        files.forEach(file -> arguments.add(file.toString()));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, log, log, arguments.toArray(String[]::new)), log.toString(StandardCharsets.UTF_8));
    }

    private static String config(String profile, Path output) {
        String modules = profile.equals("light") ? "" : """
                  "modulePaths": {
                    "domain": "stellar-enclosure-source-%s-domain",
                    "application": "stellar-enclosure-source-%s-application",
                    "infrastructure": "stellar-enclosure-source-%s-infrastructure",
                    "adapter": "stellar-enclosure-source-%s-adapter"
                  },
                """.formatted(profile, profile, profile, profile);
        return """
                {
                  "configVersion": 1,
                  "projectType": "%s",
                  "basePackage": "com.example.codegenfixture",
                  "domain": "codegenfixture",
                  "outputRoot": "%s",
                %s
                  "input": {"mode": "schema", "schemaFiles": ["%s"]},
                  "logicalTables": ["orders"],
                  "artifacts": ["backend-crud"],
                  "existingTypeMappings": {},
                  "fieldPolicies": {"create": ["code"], "update": ["code"], "result": ["code"], "filter": ["code"], "sort": ["id"]},
                  "apiContract": {
                    "existingErrorMapper": "com.example.codegenfixture.support.FixtureErrorMapper",
                    "contextSymbol": "com.example.codegenfixture.support.FixtureContext",
                    "basePath": "/api/codegenfixture"
                  },
                  "events": {"enabled": false}
                }
                """.formatted(profile, output, modules,
                Path.of("src/test/resources/ddl/schema.sql").toAbsolutePath());
    }

    private static Path jar(String relative) {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("maven.repo.local", Path.of(System.getProperty("user.home"), ".m2/repository").toString())).resolve(relative),
                Path.of(System.getProperty("user.home"), "maven/repository").resolve(relative),
                Path.of(System.getProperty("user.home"), ".m2/repository").resolve(relative));
        return candidates.stream().filter(Files::exists).findFirst().orElseThrow();
    }
}
