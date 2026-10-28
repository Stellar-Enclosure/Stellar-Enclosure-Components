package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.codegen.ddl.DdlSchemaService;
import top.egon.cola.component.codegen.ddl.PostgreDdlAdapter;
import top.egon.cola.component.codegen.model.CodegenConfigBO;
import top.egon.cola.component.codegen.model.CodegenProfileEnum;
import top.egon.cola.component.codegen.model.CodegenSchemaBO;
import top.egon.cola.component.codegen.profile.ProjectLayoutStrategy;
import top.egon.cola.component.codegen.template.FreeMarkerTemplateService;
import top.egon.cola.component.codegen.validation.GenerationScopeValidator;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCrudTemplateTest {

    private static final List<String> BACKEND = List.of(
            "domain-model", "domain-query", "command", "query", "result", "converter",
            "domain-service", "domain-impl", "manage", "manage-impl", "po", "dao", "mapper-xml", "repo");

    private static CodegenSchemaBO orders;

    private final GenerationScopeValidator scope = new GenerationScopeValidator();

    private final FreeMarkerTemplateService templates = new FreeMarkerTemplateService();

    @BeforeAll
    static void loadOrders() {
        orders = new DdlSchemaService(new PostgreDdlAdapter()).read(CodegenConfigBO.builder()
                .input(CodegenConfigBO.InputBO.builder().mode("schema")
                        .schemaFiles(List.of("src/test/resources/ddl/schema.sql")).build())
                .build());
    }

    @Test
    void layeredCrudKeepsBoundariesAndVersion(@TempDir Path output) throws Exception {
        Map<String, String> light = render(config(CodegenProfileEnum.LIGHT, withController(true)));
        Map<String, String> service = render(config(CodegenProfileEnum.SERVICE, withController(false)));
        assertFalse(service.containsKey("controller"));
        assertTrue(light.containsKey("controller"));
        assertFalse(ProjectLayoutStrategy.resolve(CodegenProfileEnum.SERVICE).allows("controller"));

        assertFalse(light.get("domain-service").contains(".po."));
        assertFalse(light.get("domain-service").contains("mybatis"));
        assertFalse(light.get("domain-model").contains("EgonModel"));
        assertFalse(light.get("domain-impl").contains(".application."));
        assertTrue(light.get("repo").contains("selectByQuery"));
        assertTrue(light.get("domain-impl").contains("repository.selectByQuery"));
        assertFalse(light.get("domain-impl").contains("getBaseMapper()"));
        assertFalse(light.get("controller").contains("Repository"));
        assertFalse(light.get("controller").contains("DomainService"));
        assertTrue(light.get("controller").contains("manage.detail"));
        assertTrue(light.get("controller").contains("manage.page"));
        assertFalse(light.get("controller").contains("repository."));
        assertFalse(light.get("create-command").contains("tenantId"));
        assertFalse(light.get("create-command").contains("createTime"));
        assertFalse(light.get("result").contains("tenantId"));
        assertTrue(light.get("update-converter").contains("expectedVersion"));
        assertTrue(light.get("domain-impl").contains("po.setVersion(value.getVersion())"));
        assertTrue(light.get("domain-impl").contains("repository.removeById(po)"));
        assertFalse(light.get("domain-impl").contains("removeById(id)"));
        assertFalse(light.get("domain-impl").contains("removeById(value.getId())"));
        assertTrue(light.get("domain-impl").contains("zeroRows"));
        assertTrue(light.get("manage-impl").contains("updateOrdersCmdExe.execute(command)"));
        assertTrue(light.get("update-executor").contains("domain.getVersion()"));
        assertTrue(light.get("page-executor").contains("pageConverter.toTarget(query)"));
        assertFalse(light.get("page-executor").contains("domainService.changeOrders("));

        exerciseManage(light, config(CodegenProfileEnum.LIGHT, withController(true)), output);
    }

    @Test
    void temporalCrudArtifactsCompileWithRealConverters(@TempDir Path output) throws Exception {
        CodegenSchemaBO schema = TimeTypeMappingTest.temporalSchema();
        for (CodegenProfileEnum profile : List.of(CodegenProfileEnum.LIGHT, CodegenProfileEnum.WEB, CodegenProfileEnum.SERVICE)) {
            CodegenConfigBO config = config(profile, withController(false));
            config.setFieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder()
                    .create(TimeTypeMappingTest.TIME_FIELDS).update(TimeTypeMappingTest.TIME_FIELDS)
                    .result(TimeTypeMappingTest.TIME_FIELDS).filter(List.of("code")).sort(List.of("id")).build());
            Map<String, String> sources = render(config, schema);
            assertTrue(sources.get("po").contains("java.time.Instant awareAt"));
            assertTrue(sources.get("po").contains("java.time.LocalDateTime localAt"));
            assertTrue(sources.get("po").contains("java.time.LocalDate onDate"));
            assertTrue(sources.get("po").contains("java.time.LocalTime atTime"));
            assertTrue(sources.get("create-command").contains("Instant awareAt"));
            assertTrue(sources.get("result").contains("LocalDate onDate"));
            exerciseManage(sources, config, output.resolve(profile.code()));
        }
    }

    @Test
    void databaseDefaultReturnedByCreateRequiresExplicitInput(@TempDir Path output) throws Exception {
        String ddl = Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64) DEFAULT 'generated'");
        Path schemaFile = output.resolve("schema.sql");
        Files.writeString(schemaFile, ddl);
        CodegenSchemaBO schema = new DdlSchemaService(new PostgreDdlAdapter()).read(CodegenConfigBO.builder()
                .input(CodegenConfigBO.InputBO.builder().mode("schema").schemaFiles(List.of(schemaFile.toString())).build())
                .build());
        CodegenConfigBO config = config(CodegenProfileEnum.LIGHT, withController(false));
        config.setFieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder().create(List.of()).update(List.of("code"))
                .result(List.of("code")).filter(List.of("code")).sort(List.of("id")).build());
        assertTrue(scope.validate(config, schema).stream()
                .anyMatch(value -> "CREATE_RESULT_DEFAULT_REQUIRES_INPUT".equals(value.getCode())));
        config.getFieldPolicies().setCreate(List.of("code"));
        Map<String, String> sources = render(config, schema);
        assertTrue(sources.get("create-command").contains("@NotNull\n    private String code;"));
        assertTrue(sources.get("domain-impl").contains("CREATE_RESULT_VALUE_REQUIRED: code"));
        exerciseManage(sources, config, output.resolve("default"));
    }

    @Test
    void childShardUsesReplicaCandidateAndImmutableRootForVersionedWrites(@TempDir Path output) throws Exception {
        String ddl = Files.readString(Path.of("src/test/resources/ddl/schema.sql"))
                .replace("code VARCHAR(64)", "code VARCHAR(64), order_id BIGINT NOT NULL");
        Path schemaFile = output.resolve("schema.sql");
        Files.writeString(schemaFile, ddl);
        CodegenConfigBO config = config(CodegenProfileEnum.LIGHT, withController(true));
        config.setInput(CodegenConfigBO.InputBO.builder().mode("schema").schemaFiles(List.of(schemaFile.toString()))
                .secondaryShardingColumns(Map.of("orders", "order_id")).build());
        config.getApiContract().setTables(Map.of("orders", TestContracts.apiTable("orders", "/api/v1/orders", "code", "orderId")));
        config.setFieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder().create(List.of("code", "order_id"))
                .update(List.of("code")).result(List.of("code")).filter(List.of("code")).sort(List.of("id")).build());
        CodegenSchemaBO schema = new DdlSchemaService(new PostgreDdlAdapter()).read(config);
        config.getFieldPolicies().setUpdate(List.of("code", "order_id"));
        assertTrue(scope.validate(config, schema).stream().anyMatch(value -> "SHARDING_KEY".equals(value.getCode())));
        config.getFieldPolicies().setUpdate(List.of("code"));
        Map<String, String> sources = render(config, schema);
        assertTrue(sources.get("po").contains("value = \"order_id\", insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NEVER"));
        assertTrue(sources.get("mapper-xml").contains("order_id = #{et.orderId}"));
        exerciseManage(sources, config, output.resolve("generated"));
    }

    private void exerciseManage(Map<String, String> sources, CodegenConfigBO config, Path output) throws Exception {
        Path sourceRoot = output.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(output.resolve("lombok.config"),
                "lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier\n");
        TestContracts.writeSecurityStubs(sourceRoot);
        write(sourceRoot, "com/example/order/support/OrdersErrorMapper.java", """
                package com.example.order.support;
                public final class OrdersErrorMapper {
                    private OrdersErrorMapper() {}
                    public static RuntimeException zeroRows(String operation, Long id, Long version) {
                        return new IllegalStateException(operation + ":" + id + ":" + version);
                    }
                    public static RuntimeException missing(Long id) {
                        return new IllegalStateException("missing:" + id);
                    }
                }
                """);
        write(sourceRoot, "com/example/order/domain/order/service/FakeOrdersDomainService.java", """
                package com.example.order.domain.order.service;
                import com.example.order.domain.order.vos.OrdersBO;
                import com.example.order.domain.order.vos.OrdersDomainQuery;
                import top.egon.cola.component.common.core.pojo.PageSlice;
                public class FakeOrdersDomainService implements OrdersDomainService {
                    public OrdersBO updated;
                    public Long deletedVersion;
                    public OrdersDomainQuery queried;
                    public OrdersBO createOrders(OrdersBO value) { return value; }
                    public OrdersBO changeOrders(OrdersBO value) { this.updated = value; return value; }
                    public void removeOrders(Long id, Long expectedVersion) { this.deletedVersion = expectedVersion; }
                    public OrdersBO findOrders(Long id) { return new OrdersBO().setId(id); }
                    public PageSlice<OrdersBO> pageOrders(OrdersDomainQuery query) {
                        queried = query;
                        return PageSlice.of(java.util.List.of(new OrdersBO().setId(6L).setCode("page")), true);
                    }
                }
                """);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            if (source.getKey().endsWith("-xml") || "mapper-xml".equals(source.getKey())) {
                continue;
            }
            String fileName = fileName(source.getKey(), source.getValue());
            String artifact = switch (source.getKey()) {
                case "create-command", "update-command", "delete-command" -> "command";
                case "detail-query", "page-query" -> "query";
                case "create-executor", "update-executor", "delete-executor" -> "command-executor";
                case "detail-executor", "page-executor" -> "query-executor";
                case "create-converter", "update-converter", "delete-converter", "result-converter", "page-converter" -> "application-converter";
                case "create-dto", "update-dto", "page-dto" -> "adapter-dto";
                case "view" -> "adapter-vo";
                case "adapter-converter" -> "adapter-converter";
                default -> source.getKey();
            };
            String relative = ProjectLayoutStrategy.resolve(config.getProfile()).relativePath(config, artifact, fileName);
            write(output, relative, source.getValue());
        }
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        List<Path> files;
        try (var paths = Files.walk(output)) {
            files = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String classpath = System.getProperty("java.class.path") + java.io.File.pathSeparator + lombokJar()
                + java.io.File.pathSeparator + mapstructJar() + java.io.File.pathSeparator + jar("org/springframework/spring-web/"
                + org.springframework.core.SpringVersion.getVersion() + "/spring-web-" + org.springframework.core.SpringVersion.getVersion() + ".jar");
        List<String> arguments = new ArrayList<>();
        arguments.add("-classpath");
        arguments.add(classpath);
        arguments.add("-processorpath");
        arguments.add(lombokJar() + java.io.File.pathSeparator + mapstructProcessor());
        arguments.add("-d");
        arguments.add(classes.toString());
        files.forEach(file -> arguments.add(file.toString()));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        int compiled = compiler.run(null, log, log, arguments.toArray(String[]::new));
        assertEquals(0, compiled, log.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(classes.resolve("com/example/order/infrastructure/order/converter/OrdersPersistenceConverterImpl.class")));
        assertFalse(Files.exists(classes.resolve("com/example/order/infrastructure/order/service/impl/OrdersPersistenceConverterImpl.class")));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> manageClass = loader.loadClass("com.example.order.application.order.manage.impl.OrdersManageImpl");
            Object fake = loader.loadClass("com.example.order.domain.order.service.FakeOrdersDomainService").getConstructor().newInstance();
            Object createConverter = loader.loadClass("com.example.order.application.order.pojo.convertor.CreateOrdersCommandConverterImpl").getConstructor().newInstance();
            Object updateConverter = loader.loadClass("com.example.order.application.order.pojo.convertor.UpdateOrdersCommandConverterImpl").getConstructor().newInstance();
            Object deleteConverter = loader.loadClass("com.example.order.application.order.pojo.convertor.DeleteOrdersCommandConverterImpl").getConstructor().newInstance();
            Object resultConverter = loader.loadClass("com.example.order.application.order.pojo.convertor.OrdersResultConverterImpl").getConstructor().newInstance();
            Object pageConverter = loader.loadClass("com.example.order.application.order.pojo.convertor.OrdersPageQueryConverterImpl").getConstructor().newInstance();
            Object createExecutor = executor(loader, "command.CreateOrdersCmdExe", fake, createConverter, resultConverter);
            Object updateExecutor = executor(loader, "command.UpdateOrdersCmdExe", fake, updateConverter, resultConverter);
            Object deleteExecutor = executor(loader, "command.DeleteOrdersCmdExe", fake, deleteConverter);
            Object detailExecutor = executor(loader, "query.OrdersDetailQueryExe", fake, resultConverter);
            Object pageExecutor = executor(loader, "query.OrdersPageQueryExe", fake, resultConverter, pageConverter);
            Constructor<?> constructor = manageClass.getDeclaredConstructors()[0];
            assertTrue(constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class));
            for (var parameter : constructor.getParameters()) {
                assertTrue(parameter.isAnnotationPresent(org.springframework.beans.factory.annotation.Qualifier.class), parameter.toString());
            }
            Object manage = constructor.newInstance(createExecutor, updateExecutor, deleteExecutor, detailExecutor, pageExecutor);
            var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            Class<?> updateCommand = loader.loadClass("com.example.order.application.order.pojo.command.UpdateOrdersCommand");
            assertEquals(1, validator.forExecutables().validateParameters(manage,
                    manageClass.getMethod("update", updateCommand), new Object[]{null}).size());
            assertEquals(1, validator.forExecutables().validateParameters(updateExecutor,
                    updateExecutor.getClass().getMethod("execute", updateCommand), new Object[]{null}).size());
            Class<?> implementationType = loader.loadClass("com.example.order.infrastructure.order.service.impl.OrdersDomainServiceImpl");
            Object implementation = implementationType.getDeclaredConstructors()[0].newInstance(null, null);
            Class<?> domainType = loader.loadClass("com.example.order.domain.order.vos.OrdersBO");
            assertEquals(1, validator.forExecutables().validateParameters(implementation,
                    implementationType.getMethod("createOrders", domainType), new Object[]{null}).size());
            if (sources.containsKey("controller") && !sources.get("domain-impl").contains("repository.updateRouted(po)")) {
                exerciseReplicaSafeWrites(loader, implementationType, domainType);
            }
            if (sources.get("domain-impl").contains("CREATE_RESULT_VALUE_REQUIRED: code")) {
                exerciseDefaultGuard(loader, implementationType, domainType);
            }
            if (sources.get("domain-impl").contains("repository.updateRouted(po)")) {
                exerciseSecondaryRouting(loader, implementationType, domainType, sources.get("mapper-xml"));
            }
            Object command = updateCommand.getConstructor().newInstance();
            updateCommand.getMethod("setId", Long.class).invoke(command, 5L);
            updateCommand.getMethod("setExpectedVersion", Long.class).invoke(command, 7L);
            updateCommand.getMethod("setCode", String.class).invoke(command, "A-1");
            manageClass.getMethod("update", updateCommand).invoke(manage, command);
            Object updated = fake.getClass().getField("updated").get(fake);
            assertEquals(7L, updated.getClass().getMethod("getVersion").invoke(updated));
            Class<?> deleteCommand = loader.loadClass("com.example.order.application.order.pojo.command.DeleteOrdersCommand");
            Object deletion = deleteCommand.getConstructor().newInstance();
            deleteCommand.getMethod("setId", Long.class).invoke(deletion, 5L);
            deleteCommand.getMethod("setExpectedVersion", Long.class).invoke(deletion, 9L);
            manageClass.getMethod("delete", deleteCommand).invoke(manage, deletion);
            assertEquals(9L, fake.getClass().getField("deletedVersion").get(fake));

            Class<?> pageQueryType = loader.loadClass("com.example.order.application.order.pojo.query.OrdersPageQuery");
            Object pageQuery = pageQueryType.getConstructor().newInstance();
            pageQueryType.getMethod("setCode", String.class).invoke(pageQuery, "filtered");
            Object slice = manageClass.getMethod("page", pageQueryType).invoke(manage, pageQuery);
            assertEquals(true, slice.getClass().getMethod("hasNext").invoke(slice));
            List<?> records = (List<?>) slice.getClass().getMethod("records").invoke(slice);
            assertEquals(1, records.size());
            assertEquals("6", records.getFirst().getClass().getMethod("getId").invoke(records.getFirst()));
            Object domainQuery = fake.getClass().getField("queried").get(fake);
            assertEquals("filtered", domainQuery.getClass().getMethod("getCode").invoke(domainQuery));

            if (sources.containsKey("adapter-converter")) {
                ObjectMapper json = new ObjectMapper();
                Class<?> dtoType = loader.loadClass("com.example.order.adapter.order.pojo.dto.UpdateOrdersDTO");
                Object dto = dtoType.getConstructor().newInstance();
                dtoType.getMethod("setId", Long.class).invoke(dto, 5L);
                dtoType.getMethod("setExpectedVersion", Long.class).invoke(dto, 7L);
                dtoType.getMethod("setCode", String.class).invoke(dto, "A-1");
                Class<?> mapperType = loader.loadClass("com.example.order.adapter.order.pojo.convertor.OrdersAdapterConverterImpl");
                Object adapterMapper = mapperType.getConstructor().newInstance();
                Object mappedCommand = mapperType.getMethod("toUpdateCommand", dtoType).invoke(adapterMapper, dto);
                assertEquals(json.valueToTree(command), json.valueToTree(mappedCommand));

                Class<?> resultType = loader.loadClass("com.example.order.application.order.pojo.result.OrdersResult");
                Object result = resultType.getConstructor().newInstance();
                resultType.getMethod("setId", String.class).invoke(result, "5");
                resultType.getMethod("setCode", String.class).invoke(result, "A-1");
                Object view = mapperType.getMethod("toView", resultType).invoke(adapterMapper, result);
                assertEquals(json.valueToTree(result), json.valueToTree(view));
                resultType.getMethod("setVersion", Long.class).invoke(result, 8L);
                view = mapperType.getMethod("toView", resultType).invoke(adapterMapper, result);
                assertEquals(8L, view.getClass().getMethod("getVersion").invoke(view));

                Class<?> controllerType = loader.loadClass("com.example.order.adapter.order.controller.OrdersController");
                assertEquals(java.util.Set.of("permissionExecutionGuard", "permissionJsonMapperFactory", "cqersPermissionMvcConfiguration"),
                        java.util.Set.of(controllerType.getAnnotation(org.springframework.context.annotation.DependsOn.class).value()));
                Object controller = controllerType.getDeclaredConstructors()[0].newInstance(manage, adapterMapper);
                Object noBodyId = dtoType.getConstructor().newInstance();
                dtoType.getMethod("setExpectedVersion", Long.class).invoke(noBodyId, 7L);
                dtoType.getMethod("setCode", String.class).invoke(noBodyId, "changed");
                assertTrue(validator.validate(noBodyId).isEmpty());
                Method update = controllerType.getMethod("update", String.class, dtoType);
                Object updateView = update.invoke(controller, "5", noBodyId);
                assertEquals(7L, updateView.getClass().getMethod("getVersion").invoke(updateView));
                assertTrue(java.util.Arrays.stream(update.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".PreAuthorize")));
                assertTrue(java.util.Arrays.stream(update.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".PermissionResource")));
                assertTrue(java.util.Arrays.stream(update.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".RequiresPermissions")));
                assertTrue(java.util.Arrays.stream(view.getClass().getDeclaredField("version").getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".FieldPermissions")));
                var write = java.util.Arrays.stream(dtoType.getDeclaredField("code").getAnnotations())
                        .filter(annotation -> annotation.annotationType().getName().endsWith(".FieldPermissions")).findFirst().orElseThrow();
                Object writeGroup = write.annotationType().getMethod("write").invoke(write);
                assertEquals(List.of("fixture:orders:field:write"),
                        List.of((String[]) loader.loadClass("top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup")
                                .getMethod("codes").invoke(writeGroup)));
                dtoType.getMethod("setId", Long.class).invoke(noBodyId, 6L);
                assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> update.invoke(controller, "5", noBodyId)).getCause() instanceof IllegalArgumentException);
                Class<?> pageDtoType = loader.loadClass("com.example.order.adapter.order.pojo.dto.OrdersPageDTO");
                Object pageRequest = pageDtoType.getConstructor().newInstance();
                var binder = new org.springframework.validation.DataBinder(pageRequest);
                binder.bind(new org.springframework.beans.MutablePropertyValues(Map.of(
                        "page.pageNo", "2", "page.pageSize", "3", "code", "filtered")));
                assertFalse(binder.getBindingResult().hasErrors());
                Method page = controllerType.getMethod("page", pageDtoType, Integer.class, Integer.class, Integer.class, Integer.class);
                page.invoke(controller, pageRequest, null, null, 2, 3);
                Object reboundQuery = fake.getClass().getField("queried").get(fake);
                Object reboundPage = reboundQuery.getClass().getMethod("getPage").invoke(reboundQuery);
                assertEquals(2, reboundPage.getClass().getMethod("pageNo").invoke(reboundPage));
                assertEquals(3, reboundPage.getClass().getMethod("pageSize").invoke(reboundPage));
                assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> page.invoke(controller, pageRequest, 2, null, 4, null)).getCause() instanceof IllegalArgumentException);
            }
        }
    }

    private static void exerciseReplicaSafeWrites(ClassLoader loader, Class<?> implementationType, Class<?> domainType) throws Exception {
        Class<?> repositoryType = loader.loadClass("com.example.order.infrastructure.order.repo.OrdersRepository");
        Class<?> poType = loader.loadClass("com.example.order.infrastructure.order.po.OrdersPO");
        Object converter = loader.loadClass("com.example.order.infrastructure.order.converter.OrdersPersistenceConverterImpl")
                .getConstructor().newInstance();
        var snapshot = new java.util.concurrent.atomic.AtomicReference<Object>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var updates = new java.util.concurrent.atomic.AtomicInteger();
        Object repository = org.mockito.Mockito.mock(repositoryType, call -> {
            if (call.getMethod().getName().equals("save")) {
                Object po = call.getArgument(0);
                poType.getMethod("setId", Long.class).invoke(po, 5L);
                poType.getMethod("setVersion", Long.class).invoke(po, 0L);
                return true;
            }
            if (call.getMethod().getName().equals("getById")) {
                reads.incrementAndGet();
                return snapshot.get();
            }
            if (call.getMethod().getName().equals("updateById")) {
                updates.incrementAndGet();
                Object po = call.getArgument(0);
                Long version = (Long) poType.getMethod("getVersion").invoke(po);
                poType.getMethod("setVersion", Long.class).invoke(po, version + 1);
                return true;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        Object service = implementationType.getDeclaredConstructors()[0].newInstance(repository, converter);
        Object create = domainType.getConstructor().newInstance();
        domainType.getMethod("setCode", String.class).invoke(create, "created");
        Object created = implementationType.getMethod("createOrders", domainType).invoke(service, create);
        assertEquals(0, reads.get(), "create must not reread an uncommitted row from a replica");
        assertEquals(5L, domainType.getMethod("getId").invoke(created));
        assertEquals(0L, domainType.getMethod("getVersion").invoke(created));
        assertEquals("created", domainType.getMethod("getCode").invoke(created));

        Object current = poType.getConstructor().newInstance();
        poType.getMethod("setId", Long.class).invoke(current, 5L);
        poType.getMethod("setVersion", Long.class).invoke(current, 3L);
        poType.getMethod("setCode", String.class).invoke(current, "unchanged");
        snapshot.set(current);
        Object update = domainType.getConstructor().newInstance();
        domainType.getMethod("setId", Long.class).invoke(update, 5L);
        domainType.getMethod("setVersion", Long.class).invoke(update, 3L);
        Object updated = implementationType.getMethod("changeOrders", domainType).invoke(service, update);
        assertEquals(1, reads.get(), "update must use only the observed candidate");
        assertEquals(4L, domainType.getMethod("getVersion").invoke(updated));
        assertEquals("unchanged", domainType.getMethod("getCode").invoke(updated));
        assertEquals(1, updates.get());

        Object stale = domainType.getConstructor().newInstance();
        domainType.getMethod("setId", Long.class).invoke(stale, 5L);
        domainType.getMethod("setVersion", Long.class).invoke(stale, 3L);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> implementationType.getMethod("changeOrders", domainType).invoke(service, stale))
                .getCause() instanceof IllegalStateException);
        assertEquals(1, updates.get(), "a stale replica snapshot must not be used for a write");
    }

    private static void exerciseDefaultGuard(ClassLoader loader, Class<?> implementationType, Class<?> domainType) throws Exception {
        Class<?> repositoryType = loader.loadClass("com.example.order.infrastructure.order.repo.OrdersRepository");
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        Object repository = org.mockito.Mockito.mock(repositoryType, call -> {
            if (call.getMethod().getName().equals("save")) wrote.set(true);
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        Object converter = loader.loadClass("com.example.order.infrastructure.order.converter.OrdersPersistenceConverterImpl")
                .getConstructor().newInstance();
        Object service = implementationType.getDeclaredConstructors()[0].newInstance(repository, converter);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> implementationType.getMethod("createOrders", domainType).invoke(service, domainType.getConstructor().newInstance()));
        assertEquals("CREATE_RESULT_VALUE_REQUIRED: code", failure.getCause().getMessage());
        assertFalse(wrote.get());
    }

    private static void exerciseSecondaryRouting(ClassLoader loader, Class<?> implementationType, Class<?> domainType,
                                                 String mapperXml) throws Exception {
        Class<?> repositoryType = loader.loadClass("com.example.order.infrastructure.order.repo.OrdersRepository");
        Class<?> poType = loader.loadClass("com.example.order.infrastructure.order.po.OrdersPO");
        Object current = poType.getConstructor().newInstance();
        poType.getMethod("setId", Long.class).invoke(current, 5L);
        poType.getMethod("setOrderId", Long.class).invoke(current, 42L);
        poType.getMethod("setVersion", Long.class).invoke(current, 7L);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        Object repository = org.mockito.Mockito.mock(repositoryType, call -> {
            if (call.getMethod().getName().equals("getById")) return current;
            if (call.getMethod().getName().equals("updateRouted")) {
                Object entity = call.getArgument(0);
                assertEquals(42L, poType.getMethod("getOrderId").invoke(entity));
                assertEquals(7L, poType.getMethod("getVersion").invoke(entity));
                poType.getMethod("setVersion", Long.class).invoke(entity, 8L);
                writes.incrementAndGet();
                return true;
            }
            if (call.getMethod().getName().equals("removeById")) {
                Object entity = call.getArgument(0);
                assertEquals(42L, poType.getMethod("getOrderId").invoke(entity));
                assertEquals(8L, poType.getMethod("getVersion").invoke(entity));
                writes.incrementAndGet();
                return true;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        Object converter = loader.loadClass("com.example.order.infrastructure.order.converter.OrdersPersistenceConverterImpl")
                .getConstructor().newInstance();
        Object service = implementationType.getDeclaredConstructors()[0].newInstance(repository, converter);
        Object update = domainType.getConstructor().newInstance();
        domainType.getMethod("setId", Long.class).invoke(update, 5L);
        domainType.getMethod("setVersion", Long.class).invoke(update, 7L);
        domainType.getMethod("setCode", String.class).invoke(update, "updated");
        implementationType.getMethod("changeOrders", domainType).invoke(service, update);
        implementationType.getMethod("removeOrders", Long.class, Long.class).invoke(service, 5L, 8L);
        assertEquals(2, writes.get());
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> implementationType.getMethod("removeOrders", Long.class, Long.class).invoke(service, 5L, 7L))
                .getCause() instanceof IllegalStateException);
        assertEquals(2, writes.get());
        verifyGeneratedSqlGuard(loader, poType, mapperXml);
    }

    private static void verifyGeneratedSqlGuard(ClassLoader loader, Class<?> poType, String mapperXml) throws Exception {
        var previousLoader = Thread.currentThread().getContextClassLoader();
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider.initialize("tenantId");
        org.slf4j.MDC.put("tenantId", "41");
        try (var validationFactory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Thread.currentThread().setContextClassLoader(loader);
            var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
            configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                    new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), source));
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    new java.io.ByteArrayInputStream(mapperXml.getBytes(StandardCharsets.UTF_8)), configuration,
                    "generated-orders.xml", configuration.getSqlFragments()).parse();
            var properties = new top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties();
            var tenant = new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                    new top.egon.cola.component.common.mybatis.business.EgonColaTenantIdTenantLineHandler(properties));
            var sqlGuard = new top.egon.cola.component.common.mybatis.interceptor.EgonColaTenantIdGuardInnerInterceptor(
                    () -> "actor", properties);
            var profileProps = new java.util.Properties();
            profileProps.setProperty("logical-table", "orders");
            profileProps.setProperty("tenant-slot-count", "1");
            profileProps.setProperty("secondary-bucket-count", "2");
            profileProps.setProperty("tenant-slot-map", "0=shard_0");
            profileProps.setProperty("actual-data-nodes", "shard_0.public.orders_t0_b0,shard_0.public.orders_t0_b1");
            profileProps.setProperty("secondary-column", "order_id");
            profileProps.setProperty("root-key-name", "order_id");
            profileProps.setProperty("secondary-seed", "0x9e3779b97f4a7c15");
            profileProps.setProperty("algorithm-version", "mix64-v1");
            profileProps.setProperty("max-read-fanout-tables", "2");
            var profile = top.egon.cola.component.common.mybatis.sharding.resolver.EgonColaShardingWriteTargetResolver.profile(profileProps);
            var strategy = org.mockito.Mockito.spy(new top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy(
                    new top.egon.cola.component.common.core.validation.ValidationUtils(validationFactory.getValidator())));
            var guard = new top.egon.cola.component.common.mybatis.interceptor.EgonColaLocalWriteGuardInnerInterceptor(
                    query -> { throw new AssertionError("unexpected legacy routing"); }, sqlGuard, strategy, Map.of("orders", profile));
            Object entity = poType.getConstructor().newInstance();
            poType.getMethod("setId", Long.class).invoke(entity, 5L);
            poType.getMethod("setOrderId", Long.class).invoke(entity, 42L);
            poType.getMethod("setVersion", Long.class).invoke(entity, 8L);
            poType.getMethod("setCode", String.class).invoke(entity, "changed");
            poType.getMethod("setUpdateUserId", String.class).invoke(entity, "actor");
            poType.getMethod("setUpdateTime", java.time.Instant.class).invoke(entity, java.time.Instant.parse("2026-01-01T00:00:00Z"));
            for (String method : List.of("updateRouted", "deleteVersionedById")) {
                var statement = configuration.getMappedStatement("com.example.order.infrastructure.order.dao.OrdersDAO." + method);
                var params = new java.util.HashMap<String, Object>();
                params.put("et", entity);
                params.put("MP_OPTLOCK_VERSION_ORIGINAL", 7L);
                var handler = configuration.newStatementHandler(org.mockito.Mockito.mock(org.apache.ibatis.executor.Executor.class),
                        statement, params, org.apache.ibatis.session.RowBounds.DEFAULT, null, statement.getBoundSql(params));
                var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
                sqlGuard.beforePrepare(handler, connection, null);
                tenant.beforePrepare(handler, connection, null);
                guard.beforePrepare(handler, connection, null);
            }
            var captured = org.mockito.ArgumentCaptor.forClass(top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery.class);
            org.mockito.Mockito.verify(strategy, org.mockito.Mockito.times(2)).route(org.mockito.Mockito.eq(profile), captured.capture());
            assertEquals(List.of(List.of(42L), List.of(42L)), captured.getAllValues().stream().map(query -> query.secondaryValues()).toList());
            poType.getMethod("setOrderId", Long.class).invoke(entity, (Object) null);
            var statement = configuration.getMappedStatement("com.example.order.infrastructure.order.dao.OrdersDAO.updateRouted");
            var params = new java.util.HashMap<String, Object>();
            params.put("et", entity);
            params.put("MP_OPTLOCK_VERSION_ORIGINAL", 7L);
            var handler = configuration.newStatementHandler(org.mockito.Mockito.mock(org.apache.ibatis.executor.Executor.class),
                    statement, params, org.apache.ibatis.session.RowBounds.DEFAULT, null, statement.getBoundSql(params));
            var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
            sqlGuard.beforePrepare(handler, connection, null);
            tenant.beforePrepare(handler, connection, null);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> guard.beforePrepare(handler, connection, null));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            org.slf4j.MDC.clear();
            if (previousMdc != null) org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static Object executor(ClassLoader loader, String name, Object... dependencies) throws Exception {
        Class<?> type = loader.loadClass("com.example.order.application.order.executor." + name);
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        assertTrue(constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class));
        for (var parameter : constructor.getParameters()) {
            assertTrue(parameter.isAnnotationPresent(org.springframework.beans.factory.annotation.Qualifier.class), parameter.toString());
        }
        return constructor.newInstance(dependencies);
    }

    private Map<String, String> render(CodegenConfigBO config) {
        return render(config, orders);
    }

    private Map<String, String> render(CodegenConfigBO config, CodegenSchemaBO schema) {
        assertTrue(scope.validate(config, schema).isEmpty());
        CodegenSchemaBO.TableBO table = schema.getTables().get(0);
        Map<String, String> rendered = new LinkedHashMap<>();
        for (String artifact : config.getArtifacts()) {
            Map<String, Object> model = new LinkedHashMap<>(scope.renderModel(config, table, artifact));
            switch (artifact) {
                case "command" -> {
                    rendered.put("create-command", text("command", model, "commandKind", "create"));
                    rendered.put("update-command", text("command", model, "commandKind", "update"));
                    rendered.put("delete-command", text("command", model, "commandKind", "delete"));
                    rendered.put("create-executor", text("command-executor", model, "executorKind", "create"));
                    rendered.put("update-executor", text("command-executor", model, "executorKind", "update"));
                    rendered.put("delete-executor", text("command-executor", model, "executorKind", "delete"));
                }
                case "query" -> {
                    rendered.put("detail-query", text("query", model, "queryKind", "detail"));
                    rendered.put("page-query", text("query", model, "queryKind", "page"));
                    rendered.put("detail-executor", text("query-executor", model, "executorKind", "detail"));
                    rendered.put("page-executor", text("query-executor", model, "executorKind", "page"));
                }
                case "converter" -> {
                    rendered.put("persistence-converter", text("converter", model, "converterKind", "persistence"));
                    rendered.put("create-converter", text("converter", model, "converterKind", "create"));
                    rendered.put("update-converter", text("converter", model, "converterKind", "update"));
                    rendered.put("delete-converter", text("converter", model, "converterKind", "delete"));
                    rendered.put("result-converter", text("converter", model, "converterKind", "result"));
                    rendered.put("page-converter", text("converter", model, "converterKind", "page"));
                }
                case "controller" -> {
                    rendered.put("controller", new String(templates.render("controller", model), StandardCharsets.UTF_8));
                    rendered.put("create-dto", text("adapter-dto", model, "dtoKind", "create"));
                    rendered.put("update-dto", text("adapter-dto", model, "dtoKind", "update"));
                    rendered.put("page-dto", text("adapter-dto", model, "dtoKind", "page"));
                    rendered.put("view", new String(templates.render("adapter-vo", model), StandardCharsets.UTF_8));
                    rendered.put("adapter-converter", new String(templates.render("adapter-converter", model), StandardCharsets.UTF_8));
                }
                default -> rendered.put(artifact, new String(templates.render(artifact, model), StandardCharsets.UTF_8));
            }
        }
        return rendered;
    }

    private String text(String templateId, Map<String, Object> model, String key, String value) {
        Map<String, Object> copy = new LinkedHashMap<>(model);
        copy.put(key, value);
        return new String(templates.render(templateId, copy), StandardCharsets.UTF_8);
    }

    private static CodegenConfigBO config(CodegenProfileEnum profile, List<String> artifacts) {
        return CodegenConfigBO.builder()
                .configVersion(1)
                .profile(profile)
                .basePackage("com.example.order")
                .domain("order")
                .outputRoot("target/codegen-backend-no-write")
                .artifacts(new ArrayList<>(artifacts))
                .tables(new ArrayList<>(List.of("orders")))
                .fieldPolicies(CodegenConfigBO.FieldPoliciesBO.builder()
                        .create(List.of("code")).update(List.of("code")).result(List.of("code"))
                        .filter(List.of("code")).sort(List.of("id")).build())
                .domainMethods(Map.of("orders", TestContracts.methods("Orders")))
                .apiContract(CodegenConfigBO.ApiContractBO.builder()
                        .existingErrorMapper("com.example.order.support.OrdersErrorMapper")
                        .contextSymbol("com.example.order.support.OrdersContext")
                        .basePath("/api/v1/orders")
                        .transportMode("generate")
                        .permissionReferences(TestContracts.permissions("orders"))
                        .tables(Map.of("orders", TestContracts.apiTable("orders", "/api/v1/orders", "code"))).build())
                .roots(profile == CodegenProfileEnum.LIGHT ? null : new LinkedHashMap<>(Map.of(
                        "infrastructure", "order-service-infrastructure",
                        "domain", "order-service-domain",
                        "application", "order-service-application",
                        "adapter", "order-service-adapter")))
                .build();
    }

    private static List<String> withController(boolean controller) {
        List<String> artifacts = new ArrayList<>(BACKEND);
        if (controller) {
            artifacts.add("controller");
        }
        return artifacts;
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String fileName(String key, String source) {
        int classAt = source.indexOf("public class ");
        int interfaceAt = source.indexOf("public interface ");
        int start = classAt >= 0 ? classAt + "public class ".length() : interfaceAt + "public interface ".length();
        String name = source.substring(start, source.indexOf(' ', start)).trim();
        return name + ".java";
    }

    private static Path lombokJar() {
        return jar("org/projectlombok/lombok/1.18.46/lombok-1.18.46.jar");
    }

    private static Path mapstructJar() {
        return jar("org/mapstruct/mapstruct/1.6.3/mapstruct-1.6.3.jar");
    }

    private static Path mapstructProcessor() {
        return jar("org/mapstruct/mapstruct-processor/1.6.3/mapstruct-processor-1.6.3.jar");
    }

    private static Path jar(String relative) {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("user.home"), "maven/repository").resolve(relative),
                Path.of(System.getProperty("user.home"), ".m2/repository").resolve(relative));
        return candidates.stream().filter(Files::exists).findFirst().orElseThrow(() -> new IllegalStateException(relative));
    }
}
