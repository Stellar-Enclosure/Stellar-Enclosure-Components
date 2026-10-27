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
            if (!"service".equals(profile)) verifyPermissionTransport(project);
            assertEquals(0, run("check", "--config", config.toString()), lastErr);
        }
        String launcher = Files.readString(Path.of("src/main/java/top/egon/cola/component/codegen/cli/CodegenCommand.java"));
        assertFalse(launcher.contains("SpringApplication"));
        assertFalse(launcher.contains("DriverManager"));
    }

    private static void verifyPermissionTransport(Path project) throws Exception {
        Path mvc = jar("org/springframework/spring-webmvc/" + org.springframework.core.SpringVersion.getVersion()
                + "/spring-webmvc-" + org.springframework.core.SpringVersion.getVersion() + ".jar");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{project.resolve("classes").toUri().toURL(), mvc.toUri().toURL()},
                CodegenAcceptanceTest.class.getClassLoader())) {
            Class<?> factoryType = loader.loadClass("top.egon.cola.platform.tianquan.jianshen.starter.field.PermissionJsonMapperFactory");
            Object factory = factoryType.getConstructor().newInstance();
            ObjectMapper shared = new ObjectMapper();
            Class<?> configType = loader.loadClass("com.example.codegenfixture.adapter.config.CqersPermissionMvcConfiguration");
            Object config = configType.getDeclaredConstructors()[0].newInstance(factory, shared);
            Object bytes = loader.loadClass("org.springframework.http.converter.ByteArrayHttpMessageConverter").getConstructor().newInstance();
            List<Object> converters = new ArrayList<>();
            converters.add(bytes);
            configType.getMethod("extendMessageConverters", List.class).invoke(config, converters);
            assertEquals(bytes, converters.getFirst());
            Object json = converters.stream().filter(value -> value.getClass().getName().endsWith("MappingJackson2HttpMessageConverter"))
                    .findFirst().orElseThrow();
            ObjectMapper secured = (ObjectMapper) json.getClass().getMethod("getObjectMapper").invoke(json);
            assertTrue(secured.getRegisteredModuleIds().contains("fixture-field-guard"));
            assertFalse(shared.getRegisteredModuleIds().contains("fixture-field-guard"));
            Object existing = loader.loadClass("org.springframework.http.converter.json.MappingJackson2HttpMessageConverter")
                    .getConstructor(ObjectMapper.class).newInstance(shared);
            List<Object> existingConverters = new ArrayList<>(List.of(bytes, existing));
            configType.getMethod("extendMessageConverters", List.class).invoke(config, existingConverters);
            assertEquals(existing, existingConverters.get(1));
            ObjectMapper wrapped = (ObjectMapper) existing.getClass().getMethod("getObjectMapper").invoke(existing);
            assertTrue(wrapped.getRegisteredModuleIds().contains("fixture-field-guard"));
            assertFalse(shared.getRegisteredModuleIds().contains("fixture-field-guard"));
        }
    }

    @Test
    void emptyCreateAndUpdateFieldPoliciesStillCompile(@TempDir Path root) throws Exception {
        Path config = root.resolve("codegen.json");
        ObjectNode document = (ObjectNode) mapper.readTree(config("web", root));
        document.withObject("/fieldPolicies").putArray("create");
        document.withObject("/fieldPolicies").putArray("update");
        mapper.writeValue(config.toFile(), document);
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
        TestContracts.addJson(document, "orders", "Orders", "/api/v1/codegenfixture", "code", "amount", "dueDate");
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
        event.putArray("fields").addObject().put("name", "sourceSystem").put("javaType", "String")
                .put("required", true).put("maxLength", 32);
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
        assertTrue(eventSource.contains("String sourceSystem"));
    }

    @Test
    void partialUpdateReturnsPersistedFieldsAndNewVersion(@TempDir Path root) throws Exception {
        Path schema = root.resolve("schema.sql");
        Files.writeString(schema, Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64) NOT NULL, label VARCHAR(64) NOT NULL"));
        Path config = root.resolve("codegen.json");
        ObjectNode document = (ObjectNode) mapper.readTree(config("web", root));
        document.withObject("/input").putArray("schemaFiles").add(schema.toString());
        ObjectNode policies = document.withObject("/fieldPolicies");
        policies.putArray("create").add("code").add("label");
        policies.putArray("update").add("code");
        policies.putArray("result").add("code").add("label");
        TestContracts.addJson(document, "orders", "Orders", "/api/v1/codegenfixture", "code", "label");
        mapper.writeValue(config.toFile(), document);
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
        assertEquals(0, run("apply", "--config", config.toString(), "--plan", mapper.readTree(lastOut).path("planId").asText()), lastErr);
        Path infrastructure = root.resolve("stellar-enclosure-source-web-infrastructure/src/main/java/com/example/codegenfixture");
        Path repo = infrastructure.resolve("infrastructure/codegenfixture/repo/OrdersRepository.java");
        Files.writeString(repo, """
                package com.example.codegenfixture.infrastructure.codegenfixture.repo;
                import com.example.codegenfixture.domain.codegenfixture.vos.OrdersDomainQuery;
                import com.example.codegenfixture.infrastructure.codegenfixture.po.OrdersPO;
                public class OrdersRepository {
                    private final OrdersPO stored = new OrdersPO();
                    public OrdersRepository() { stored.setId(1L); stored.setVersion(3L); stored.setCode("old"); stored.setLabel("unchanged"); }
                    public OrdersPO getById(Long id) { return stored; }
                    public boolean updateById(OrdersPO patch) {
                        if (patch.getCode() != null) stored.setCode(patch.getCode());
                        if (patch.getLabel() != null) stored.setLabel(patch.getLabel());
                        stored.setVersion(4L); return true;
                    }
                    public boolean save(OrdersPO value) { return true; }
                    public boolean removeById(OrdersPO value) { return true; }
                    public java.util.List<OrdersPO> selectByQuery(OrdersDomainQuery query, int limit, long offset) { return java.util.List.of(); }
                }
                """);
        Path support = infrastructure.resolve("support/FixtureErrorMapper.java");
        Files.createDirectories(support.getParent());
        Files.writeString(support, """
                package com.example.codegenfixture.support;
                public final class FixtureErrorMapper {
                    public static RuntimeException missing(Long id) { return new IllegalStateException(); }
                    public static RuntimeException zeroRows(String operation, Long id, Long version) { return new IllegalStateException(); }
                }
                """);
        Path classes = root.resolve("classes");
        compile(root, classes);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Object repository = loader.loadClass("com.example.codegenfixture.infrastructure.codegenfixture.repo.OrdersRepository").getConstructor().newInstance();
            Object converter = loader.loadClass("com.example.codegenfixture.infrastructure.codegenfixture.converter.OrdersPersistenceConverterImpl").getConstructor().newInstance();
            Class<?> serviceType = loader.loadClass("com.example.codegenfixture.infrastructure.codegenfixture.service.impl.OrdersDomainServiceImpl");
            Object service = serviceType.getDeclaredConstructors()[0].newInstance(repository, converter);
            Class<?> boType = loader.loadClass("com.example.codegenfixture.domain.codegenfixture.vos.OrdersBO");
            Object patch = boType.getConstructor().newInstance();
            boType.getMethod("setId", Long.class).invoke(patch, 1L);
            boType.getMethod("setVersion", Long.class).invoke(patch, 3L);
            boType.getMethod("setCode", String.class).invoke(patch, "new");
            Object result = serviceType.getMethod("changeOrders", boType).invoke(service, patch);
            assertEquals("unchanged", boType.getMethod("getLabel").invoke(result));
            assertEquals(4L, boType.getMethod("getVersion").invoke(result));
            Class<?> commandType = loader.loadClass("com.example.codegenfixture.application.codegenfixture.pojo.command.UpdateOrdersCommand");
            Object command = commandType.getConstructor().newInstance();
            commandType.getMethod("setId", Long.class).invoke(command, 1L);
            commandType.getMethod("setExpectedVersion", Long.class).invoke(command, 3L);
            assertTrue(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(command).isEmpty());
        }
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
                    public OrdersBO createOrders(OrdersBO value) {
                        requireTransaction();
                        if (fail) throw new IllegalStateException("retry");
                        created++;
                        return value.setId(1L);
                    }
                    public OrdersBO changeOrders(OrdersBO value) { requireTransaction(); updatedVersion = value.getVersion(); return value; }
                    public void removeOrders(Long id, Long version) { requireTransaction(); deletedVersion = version; }
                    public OrdersBO findOrders(Long id) { return new OrdersBO().setId(id); }
                    public PageSlice<OrdersBO> pageOrders(OrdersDomainQuery query) { return PageSlice.of(java.util.List.of(), false); }
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

    @Test
    void standaloneBusinessEventDelegatesExplicitFieldsToExistingPort(@TempDir Path root) throws Exception {
        Path config = root.resolve("codegen.json");
        ObjectNode document = (ObjectNode) mapper.readTree(config("web", root));
        document.putArray("artifacts").add("event");
        document.putObject("input").put("mode", "event");
        document.putArray("logicalTables");
        ObjectNode event = document.putObject("events").put("enabled", true).putArray("definitions").addObject();
        event.put("name", "PaymentSettledEvent");
        event.put("action", "delegate");
        event.put("schemaVersion", "v1");
        event.put("idempotencyType", "com.example.codegenfixture.support.FixtureEventGuard");
        event.put("idempotencyBean", "fixtureEventGuard");
        event.put("idempotencyMethod", "executeOnce");
        event.put("handlerType", "com.example.codegenfixture.application.codegenfixture.service.PaymentProcessor");
        event.put("handlerBean", "paymentProcessor");
        event.put("handlerMethod", "acceptPayment");
        event.putArray("fields").addObject().put("name", "paymentId").put("javaType", "String").put("required", true).put("maxLength", 64);
        event.withArray("fields").addObject().put("name", "occurredAt").put("javaType", "java.time.Instant").put("required", true);
        event.withArray("fields").addObject().put("name", "items")
                .put("javaType", "java.util.List<com.example.codegenfixture.domain.codegenfixture.vos.PaymentItem>").put("required", true);
        event.withArray("fields").addObject().put("name", "groups")
                .put("javaType", "java.util.Map<String,java.util.List<com.example.codegenfixture.domain.codegenfixture.vos.PaymentItem>>").put("required", true);
        ObjectNode items = (ObjectNode) event.withArray("fields").get(2);
        items.put("javaType", "java.util.List<com.example.codegenfixture.infrastructure.PaymentItem>");
        mapper.writeValue(config.toFile(), document);
        assertEquals(2, run("plan", "--config", config.toString()));
        items.put("javaType", "java.util.List<com.example.codegenfixture.domain.codegenfixture.vos.PaymentItem>");
        mapper.writeValue(config.toFile(), document);
        var states = new top.egon.cola.component.codegen.update.GenerationStateRepository();
        String oldInput = "a".repeat(64);
        String oldPrefix = "V1:" + "b".repeat(64);
        states.save(root, top.egon.cola.component.codegen.update.GenerationStateRepository.StateBO.builder()
                .formatVersion(1).latestObservedSchema(oldInput).versionChecksumPrefix(oldPrefix).build());
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
        assertEquals(3, mapper.readTree(lastOut).path("fileCount").asInt());
        String plan = mapper.readTree(lastOut).path("planId").asText();
        assertEquals(0, run("apply", "--config", config.toString(), "--plan", plan), lastErr);
        assertEquals(oldInput, states.load(root).getLatestObservedSchema());
        assertEquals(oldPrefix, states.load(root).getVersionChecksumPrefix());
        Path support = root.resolve("stellar-enclosure-source-web-application/src/main/java/com/example/codegenfixture/support");
        Files.createDirectories(support);
        Files.writeString(support.resolve("FixtureEventGuard.java"), """
                package com.example.codegenfixture.support;
                public class FixtureEventGuard {
                    private final java.util.Set<String> processed = new java.util.HashSet<>();
                    public void executeOnce(String type, String id, Object event, Runnable action) {
                        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            throw new IllegalStateException("missing transaction");
                        if (!processed.contains(id)) { action.run(); processed.add(id); }
                    }
                }
                """);
        Path processorSource = root.resolve("stellar-enclosure-source-web-application/src/main/java/com/example/codegenfixture/application/codegenfixture/service/PaymentProcessor.java");
        Files.createDirectories(processorSource.getParent());
        Files.writeString(processorSource, """
                package com.example.codegenfixture.application.codegenfixture.service;
                import com.example.codegenfixture.application.codegenfixture.pojo.event.PaymentSettledEvent;
                public class PaymentProcessor {
                    public int calls;
                    public void acceptPayment(PaymentSettledEvent event) { calls++; }
                }
                """);
        Path itemSource = root.resolve("stellar-enclosure-source-web-domain/src/main/java/com/example/codegenfixture/domain/codegenfixture/vos/PaymentItem.java");
        Files.createDirectories(itemSource.getParent());
        Files.writeString(itemSource, """
                package com.example.codegenfixture.domain.codegenfixture.vos;
                public class PaymentItem { @jakarta.validation.constraints.NotBlank public String sku; }
                """);
        Path classes = root.resolve("classes");
        compile(root, classes);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            String app = "com.example.codegenfixture.application.codegenfixture.";
            Class<?> eventType = loader.loadClass(app + "pojo.event.PaymentSettledEvent");
            Object payload = eventType.getConstructor().newInstance();
            eventType.getMethod("setEventId", String.class).invoke(payload, "id-1");
            eventType.getMethod("setSchemaVersion", String.class).invoke(payload, "v1");
            var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            assertEquals(4, validator.validate(payload).size());
            eventType.getMethod("setPaymentId", String.class).invoke(payload, "p-1");
            eventType.getMethod("setOccurredAt", java.time.Instant.class).invoke(payload, java.time.Instant.EPOCH);
            Object item = loader.loadClass("com.example.codegenfixture.domain.codegenfixture.vos.PaymentItem").getConstructor().newInstance();
            eventType.getMethod("setItems", List.class).invoke(payload, List.of(item));
            eventType.getMethod("setGroups", java.util.Map.class).invoke(payload, java.util.Map.of("g", List.of(item)));
            assertEquals(2, validator.validate(payload).size());
            item.getClass().getField("sku").set(item, "sku-1");
            assertTrue(validator.validate(payload).isEmpty());
            Object guard = loader.loadClass("com.example.codegenfixture.support.FixtureEventGuard").getConstructor().newInstance();
            Object processor = loader.loadClass("com.example.codegenfixture.application.codegenfixture.service.PaymentProcessor").getConstructor().newInstance();
            Class<?> executorType = loader.loadClass(app + "executor.event.PaymentSettledEventExe");
            Object executor = transactional(executorType.getDeclaredConstructors()[0].newInstance(guard, processor),
                    new RecordingTransactionManager(), loader);
            Class<?> handlerType = loader.loadClass("com.example.codegenfixture.adapter.codegenfixture.event.PaymentSettledEventHandler");
            Object handler = handlerType.getDeclaredConstructors()[0].newInstance(executor);
            handlerType.getMethod("handle", eventType).invoke(handler, payload);
            handlerType.getMethod("handle", eventType).invoke(handler, payload);
            assertEquals(1, processor.getClass().getField("calls").getInt(processor));
        }
        document.putObject("events").put("enabled", false);
        mapper.writeValue(config.toFile(), document);
        assertEquals(8, run("check", "--config", config.toString()), lastErr);
        var review = mapper.readTree(lastOut).path("retirementReview");
        assertEquals(3, review.path("paths").size());
        Path reviewFile = root.resolve("reviewed-event-retirement.json");
        mapper.writeValue(reviewFile.toFile(), review);
        for (var path : review.path("paths")) {
            Path source = root.resolve(path.asText());
            Path backup = root.resolve("preserved-events").resolve(path.asText());
            Files.createDirectories(backup.getParent());
            Files.move(source, backup);
        }
        assertEquals(0, run("retire-legacy", "--config", config.toString(), "--paths-file", reviewFile.toString()), lastErr);
        assertEquals(oldInput, states.load(root).getLatestObservedSchema());
        assertEquals(oldPrefix, states.load(root).getVersionChecksumPrefix());
        assertEquals(0, run("plan", "--config", config.toString()), lastErr);
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
        TestContracts.writeSecurityStubs(sourceRoot.resolve("security-fixture/src/main/java"));
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
                + org.springframework.core.SpringVersion.getVersion() + ".jar")
                + java.io.File.pathSeparator + jar("org/springframework/spring-webmvc/"
                + org.springframework.core.SpringVersion.getVersion() + "/spring-webmvc-"
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

    static String config(String profile, Path output) throws Exception {
        String modules = profile.equals("light") ? "" : """
                  "modulePaths": {
                    "domain": "stellar-enclosure-source-%s-domain",
                    "application": "stellar-enclosure-source-%s-application",
                    "infrastructure": "stellar-enclosure-source-%s-infrastructure",
                    "adapter": "stellar-enclosure-source-%s-adapter"
                  },
                """.formatted(profile, profile, profile, profile);
        String raw = """
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
                    "basePath": "/api/v1/codegenfixture"
                  },
                  "events": {"enabled": false}
                }
                """.formatted(profile, output, modules,
                Path.of("src/test/resources/ddl/schema.sql").toAbsolutePath());
        var mapper = new ObjectMapper();
        ObjectNode document = (ObjectNode) mapper.readTree(raw);
        TestContracts.addJson(document, "orders", "Orders", "/api/v1/codegenfixture", "code");
        return mapper.writeValueAsString(document);
    }

    private static Path jar(String relative) {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("maven.repo.local", Path.of(System.getProperty("user.home"), ".m2/repository").toString())).resolve(relative),
                Path.of(System.getProperty("user.home"), "maven/repository").resolve(relative),
                Path.of(System.getProperty("user.home"), ".m2/repository").resolve(relative));
        return candidates.stream().filter(Files::exists).findFirst().orElseThrow();
    }
}
