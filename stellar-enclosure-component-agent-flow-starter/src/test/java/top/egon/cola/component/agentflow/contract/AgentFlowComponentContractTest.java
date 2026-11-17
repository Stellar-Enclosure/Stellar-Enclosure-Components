package top.egon.cola.component.agentflow.contract;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.api.AgentFlowExecutionCommand;
import top.egon.cola.component.agentflow.api.AgentFlowService;
import top.egon.cola.component.agentflow.api.AgentFlowSessionCommand;
import top.egon.cola.component.agentflow.api.AgentFlowSessionResult;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the flat Agent Flow starter boundary before its runtime implementation is added.
 */
class AgentFlowComponentContractTest {

    private static final Path COMPONENTS_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path PARENT_POM = COMPONENTS_ROOT.resolve("pom.xml");
    private static final Path BOM_POM = COMPONENTS_ROOT.resolve("stellar-enclosure-components-bom/pom.xml");
    private static final Path BOM_README = COMPONENTS_ROOT.resolve("stellar-enclosure-components-bom/README.md");
    private static final Path BOM_README_ZH = COMPONENTS_ROOT.resolve("stellar-enclosure-components-bom/README.zh-CN.md");
    private static final Path COMPONENTS_README = COMPONENTS_ROOT.resolve("README.md");
    private static final Path COMPONENTS_README_ZH = COMPONENTS_ROOT.resolve("README.zh-CN.md");
    private static final Path ARCHITECTURE = COMPONENTS_ROOT.resolve("egon-cola-components-architecture.md");
    private static final Path MODULE_POM = Path.of("pom.xml").toAbsolutePath().normalize();
    private static final Path SOURCE_ROOT = Path.of("src/main/java").toAbsolutePath().normalize();
    private static final Path ADAPTER_ROOT =
            SOURCE_ROOT.resolve("top/egon/cola/component/agentflow/adapter");
    private static final Set<Path> ADAPTER_PACKAGES = Set.of(ADAPTER_ROOT.resolve("adk"),
            ADAPTER_ROOT.resolve("springai"), ADAPTER_ROOT.resolve("mcp"));
    private static final Path MODULE_README = Path.of("README.md").toAbsolutePath().normalize();
    private static final Path MODULE_README_ZH = Path.of("README.zh-CN.md").toAbsolutePath().normalize();

    @Test
    void is_registered_by_parent() throws IOException {
        String parent = read(PARENT_POM);

        assertEquals(1, count(parent, "<module>stellar-enclosure-component-agent-flow-starter</module>"));
        assertTrue(parent.contains("<spring-ai.version>2.0.2</spring-ai.version>"));
        assertTrue(parent.contains("<google-adk.version>1.13.0</google-adk.version>"));
        assertTrue(parent.contains("<artifactId>spring-ai-bom</artifactId>"));
        assertTrue(parent.contains("<artifactId>google-adk-spring-ai</artifactId>"));
    }

    @Test
    void uses_exact_dependency_boundary() throws IOException {
        String module = read(MODULE_POM);

        assertTrue(module.contains("<artifactId>stellar-enclosure-component-common-core</artifactId>"));
        assertTrue(module.contains("<artifactId>spring-ai-model</artifactId>"));
        assertContainsAll(module, "<groupId>org.springframework.ai</groupId>", "<artifactId>spring-ai-client-chat</artifactId>");
        assertFalse(module.contains("spring-ai-model-chat-memory-repository-jdbc"));
        assertTrue(module.contains("<artifactId>google-adk</artifactId>"));
        assertTrue(module.contains("<artifactId>google-adk-spring-ai</artifactId>"));
        assertTrue(module.contains("<exclusions>"));
        assertTrue(module.contains("<artifactId>google-adk-dev</artifactId>"));
        assertFalse(module.contains("spring-boot-starter-webmvc"));
        assertFalse(module.contains("spring-ai-starter-mcp"));
        assertFalse(module.contains("flyway"));
        assertOptionalDependency(module, "top.egon", "stellar-enclosure-component-rag-starter", null);
        assertOptionalDependency(module, "org.springframework", "spring-jdbc", null);
        assertOptionalDependency(module, "org.postgresql", "postgresql", "42.7.8");
        assertFalse(module.contains("liquibase"));
    }

    @Test
    void does_not_expose_provider_or_secret_properties() throws IOException {
        String module = read(MODULE_POM);

        assertFalse(module.contains("api-key"));
        assertFalse(module.contains("base-url"));
        assertFalse(module.contains("spring-ai-openai"));
    }

    @Test
    void is_exported_once_by_components_bom() throws IOException {
        String bom = read(BOM_POM);

        assertEquals(1, count(bom, "<artifactId>stellar-enclosure-component-agent-flow-starter</artifactId>"));
        assertTrue(bom.contains("<version>${project.version}</version>"));
        assertFalse(bom.contains("<artifactId>spring-ai-model</artifactId>"));
        assertFalse(bom.contains("<artifactId>google-adk</artifactId>"));
        assertFalse(bom.contains("<artifactId>google-adk-spring-ai</artifactId>"));
    }

    @Test
    void documents_exact_configuration_api_and_boundaries() throws IOException {
        String english = read(MODULE_README);
        String chinese = read(MODULE_README_ZH);
        String architecture = read(ARCHITECTURE);

        String[] contract = {
                "egon.cola.component.agent-flow",
                "execution-timeout",
                "shutdown-timeout",
                "flows",
                "listFlows",
                "createSession",
                "deleteSession",
                "execute",
                "executeStream",
                "Spring AI 2.0.2",
                "Google ADK 1.13.0",
                "Flowable<Event>",
                "AgentFlowSessionBusyException",
                "AgentFlowExecutionTimeoutException",
                "in-memory",
                "cancel",
                "close",
                "MCP",
                "HTTP",
                "database"
        };
        assertContainsAll(english, contract);
        assertContainsAll(chinese, contract);
        assertContainsAll(architecture,
                "stellar-enclosure-component-agent-flow-starter",
                "Agent Flow",
                "HTTP",
                "MCP",
                "database",
                "ChatModel");
    }

    @Test
    void keeps_chinese_english_contracts_in_sync() throws IOException {
        String english = read(MODULE_README);
        String chinese = read(MODULE_README_ZH);
        String componentsEnglish = read(COMPONENTS_README);
        String componentsChinese = read(COMPONENTS_README_ZH);
        String bomEnglish = read(BOM_README);
        String bomChinese = read(BOM_README_ZH);

        String[] moduleTokens = {
                "Agent Flow",
                "agent-flow-starter",
                "enabled",
                "execution-timeout",
                "shutdown-timeout",
                "executeStream",
                "Spring AI 2.0.2",
                "Google ADK 1.13.0",
                "no retry",
                "in-memory"
        };
        assertContainsAll(english, moduleTokens);
        assertContainsAll(chinese, moduleTokens);
        assertContainsAll(componentsEnglish, "stellar-enclosure-component-agent-flow-starter", "Agent Flow");
        assertContainsAll(componentsChinese, "stellar-enclosure-component-agent-flow-starter", "Agent Flow");
        assertContainsAll(bomEnglish, "stellar-enclosure-component-agent-flow-starter", "Agent Flow");
        assertContainsAll(bomChinese, "stellar-enclosure-component-agent-flow-starter", "Agent Flow");
    }

    @Test
    void uses_semantic_java_names_and_safe_logging() throws IOException {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            return;
        }

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String source = read(javaFile);
                assertFalse(source.contains("java.util.Date"), javaFile.toString());
                assertFalse(source.contains("java.util.Calendar"), javaFile.toString());
                assertFalse(source.contains("java.text.SimpleDateFormat"), javaFile.toString());
                assertFalse(source.contains("LoggerFactory.getLogger"), javaFile.toString());
                assertFalse(source.contains("BeanUtils.copyProperties"), javaFile.toString());
                assertFalse(source.contains("com.alibaba.fastjson"), javaFile.toString());
            }
        }
    }

    @Test
    void uses_flat_functional_packages() throws IOException {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            return;
        }

        try (Stream<Path> directories = Files.walk(SOURCE_ROOT)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                String normalized = "/" + SOURCE_ROOT.relativize(directory).toString().replace('\\', '/') + "/";
                assertFalse(normalized.contains("/domain/"), normalized);
                assertFalse(normalized.contains("/application/"), normalized);
                assertFalse(normalized.contains("/infrastructure/"), normalized);
                if (normalized.contains("/adapter/") && !ADAPTER_ROOT.equals(directory)) {
                    assertTrue(ADAPTER_PACKAGES.contains(directory), normalized);
                }
                assertFalse(normalized.contains("/aggregate/"), normalized);
                assertFalse(normalized.contains("/repository/"), normalized);
                assertFalse(normalized.contains("/biz/"), normalized);
            });
        }
    }

    @Test
    void publishesExactlyTheRegisteredAdapterPackages() throws IOException {
        try (Stream<Path> directories = Files.list(ADAPTER_ROOT)) {
            assertEquals(ADAPTER_PACKAGES, Set.copyOf(directories.filter(Files::isDirectory).toList()));
        }
    }

    @Test
    void retainsLegacySignaturesAndExportsSeparateNeutralTypes() throws ReflectiveOperationException {
        assertTrue(AgentFlowService.class.isAnnotationPresent(Deprecated.class));
        assertEquals("java.util.List<top.egon.cola.component.agentflow.api.AgentFlowDescriptorDTO>",
                AgentFlowService.class.getMethod("listFlows").getGenericReturnType().getTypeName());
        assertEquals(AgentFlowSessionResult.class,
                AgentFlowService.class.getMethod("createSession", AgentFlowSessionCommand.class).getReturnType());
        assertEquals(void.class,
                AgentFlowService.class.getMethod("deleteSession", AgentFlowSessionCommand.class).getReturnType());
        assertEquals("java.util.List<com.google.adk.events.Event>",
                AgentFlowService.class.getMethod("execute", AgentFlowExecutionCommand.class)
                        .getGenericReturnType().getTypeName());
        assertEquals("io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event>",
                AgentFlowService.class.getMethod("executeStream", AgentFlowExecutionCommand.class)
                        .getGenericReturnType().getTypeName());

        String api = "top.egon.cola.component.agentflow.api.";
        Class<?> executor = assertDoesNotThrow(() -> Class.forName(api + "execution.AgentExecutor"),
                "The neutral execution contract must be published separately");
        Class<?> command = Class.forName(api + "execution.AgentExecutionCommand");
        Class<?> context = Class.forName(api + "execution.AgentExecutionContextVO");
        Class<?> handle = Class.forName(api + "execution.AgentExecutionHandle");
        assertEquals(handle, executor.getMethod("execute", command, context).getReturnType());

        for (String type : List.of("execution.AgentExecutor", "execution.AgentExecutionCommand",
                "execution.AgentExecutionContextVO", "execution.AgentExecutionHandle",
                "execution.AgentExecutionContextVO$ModelCallLifecycle", "execution.AgentExecutionContextVO$ModelCall",
                "execution.AgentExecutionContextVO$ModelCallRequestVO", "execution.AgentExecutionContextVO$ModelCallOutcomeVO",
                "execution.AgentExecutionContextVO$ToolCallHandler", "execution.AgentExecutionContextVO$ToolCallBoundary",
                "execution.AgentExecutionContextVO$ToolCallRequestVO", "execution.AgentExecutionContextVO$ToolReferenceVO",
                "execution.AgentExecutionEvent", "execution.AgentExecutionResult", "context.ContextScopeVO",
                "context.ContextQuery", "context.ContextSource", "context.ContextFragmentResult",
                "context.ContextTrimReasonEnum", "context.ContextAssemblyReportVO",
                "context.ContextAssemblyReportVO$ContextTrimDetailVO",
                "memory.MemoryOwnerTypeEnum", "memory.TextMemoryBindCommand", "memory.TextMemorySaveCommand",
                "memory.TextMemoryVO", "memory.TextMemoryService",
                "memory.DocMemoryQuery", "memory.DocMemoryVO", "memory.DocMemoryReader",
                "capability.CallPermitProvider", "capability.CallPermitVO", "session.SessionStore",
                "session.SessionStateResult", "session.SessionWriteCommand")) {
            for (Method method : Class.forName(api + type).getDeclaredMethods()) {
                String signature = method.toGenericString();
                assertFalse(signature.contains("com.google.adk"), signature);
                assertFalse(signature.contains("org.springframework.ai"), signature);
                assertFalse(signature.contains("io.reactivex"), signature);
                assertFalse(signature.contains("javax.sql"), signature);
                assertFalse(signature.contains("top.egon.cola.platform.tianji"), signature);
            }
        }
    }

    private static int count(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static void assertOptionalDependency(String pom, String group, String artifact, String version) {
        String tag = "<artifactId>" + artifact + "</artifactId>";
        assertEquals(1, count(pom, tag), artifact);
        int offset = pom.indexOf(tag);
        String dependency = pom.substring(pom.lastIndexOf("<dependency>", offset), pom.indexOf("</dependency>", offset));
        assertContainsAll(dependency, "<groupId>" + group + "</groupId>", "<optional>true</optional>");
        if (version == null) {
            assertFalse(dependency.contains("<version>"), artifact);
        } else {
            assertContainsAll(dependency, "<version>" + version + "</version>");
        }
    }

    private static void assertContainsAll(String source, String... tokens) {
        for (String token : tokens) {
            assertTrue(source.contains(token), "missing contract token: " + token);
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
