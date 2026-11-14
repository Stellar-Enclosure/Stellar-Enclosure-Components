package top.egon.cola.component.agentflow.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import top.egon.cola.component.agentflow.api.AgentFlowService;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.execution.AgentExecutor;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.memory.InMemoryMemoryStore;
import top.egon.cola.component.agentflow.integration.rag.RagContextSource;
import top.egon.cola.component.rag.api.RagRetrievalService;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OptionalClasspathTest {
    private static final String PREFIX = "egon.cola.component.agent-flow.";
    private static final String[] OPTIONAL_PACKAGES = {"org.springframework.jdbc", "org.postgresql", "top.egon.cola.component.rag"};
    private final ApplicationContextRunner component = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class, AgentFlowAutoConfiguration.class,
                    AgentExecutionAutoConfiguration.class, AgentMemoryJdbcAutoConfiguration.class, AgentMemoryRagAutoConfiguration.class))
            .withPropertyValues(PREFIX + "enabled=true", "egon.cola.component.agent-flow-registry.enabled=false");

    @Test
    void core_and_legacy_facade_start_with_only_in_memory_when_optional_packages_are_missing() {
        component.withClassLoader(new FilteredClassLoader(OPTIONAL_PACKAGES))
                .withBean("researchChatModel", ChatModel.class, () -> mock(ChatModel.class))
                .withPropertyValues("egon.cola.component.agent-flow-registry.enabled=true",
                        PREFIX + "flows.research-flow.chat-model-bean-name=researchChatModel",
                        PREFIX + "flows.research-flow.model-name=research-model",
                        PREFIX + "flows.research-flow.root-agent-name=planner",
                        PREFIX + "flows.research-flow.agents[0].name=planner",
                        PREFIX + "flows.research-flow.agents[0].instruction=Plan the work.")
                .run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentFlowService.class).hasSingleBean(AgentExecutor.class)
                    .hasSingleBean(MemoryStore.class).doesNotHaveBean("agentMemoryRagContextSource");
            assertThat(context.getBean("agentMemoryStore", MemoryStore.class)).isInstanceOf(InMemoryMemoryStore.class);
        });
    }

    @Test
    void none_does_not_create_an_in_memory_store() {
        component.withClassLoader(new FilteredClassLoader(OPTIONAL_PACKAGES))
                .withPropertyValues(PREFIX + "memory.mode=NONE")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MemoryStore.class)
                        .hasSingleBean(AgentExecutor.class));
    }

    @Test
    void host_store_prevents_implicit_in_memory_registration() {
        MemoryStore custom = mock(MemoryStore.class);
        component.withClassLoader(new FilteredClassLoader(OPTIONAL_PACKAGES))
                .withBean("hostMemory", MemoryStore.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MemoryStore.class).doesNotHaveBean("agentMemoryStore");
                    assertThat(context.getBean(MemoryStore.class)).isSameAs(custom);
                });
        verifyNoInteractions(custom);
    }

    @Test
    void selects_no_unrelated_datasource_and_fails_for_missing_named_jdbc_source() {
        DataSource unrelated = mock(DataSource.class);
        component.withBean("dataSource", DataSource.class, () -> unrelated)
                .withPropertyValues(PREFIX + "memory.mode=JDBC", PREFIX + "memory.data-source-ref=isolatedMemory",
                        PREFIX + "memory.schema=egon_agent_memory")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("isolatedMemory"));
        verifyNoInteractions(unrelated);
    }

    @Test
    void fails_clear_when_explicitly_selected_jdbc_or_driver_is_absent_without_falling_back() {
        for (String absent : new String[]{"org.springframework.jdbc", "org.postgresql"}) {
            component.withClassLoader(new FilteredClassLoader(absent))
                    .withBean("jdbcMemoryStore", MemoryStore.class, () -> mock(MemoryStore.class))
                    .withPropertyValues(PREFIX + "memory.mode=JDBC", PREFIX + "memory.data-source-ref=isolatedMemory",
                            PREFIX + "memory.schema=egon_agent_memory")
                    .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("JDBC"));
        }
    }

    @Test
    void fails_clear_for_missing_rag_classes_or_host_adapter_and_authority() {
        component.withClassLoader(new FilteredClassLoader("top.egon.cola.component.rag"))
                .withPropertyValues(PREFIX + "rag.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("RAG"));
        component.withPropertyValues(PREFIX + "rag.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("ragRetrievalService"));
        component.withBean("ragRetrievalService", RagRetrievalService.class, () -> mock(RagRetrievalService.class))
                .withPropertyValues(PREFIX + "rag.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("agentMemoryRagAuthority"));
    }

    @Test
    void opt_in_rag_requires_exact_host_beans_and_exposes_the_neutral_context_source() {
        var retrieval = mock(RagRetrievalService.class);
        var authority = mock(RagContextSource.Authority.class);
        component.withBean("ragRetrievalService", RagRetrievalService.class, () -> retrieval)
                .withBean("agentMemoryRagAuthority", RagContextSource.Authority.class, () -> authority)
                .withBean("unrelatedRetrieval", RagRetrievalService.class, () -> mock(RagRetrievalService.class))
                .withPropertyValues(PREFIX + "rag.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ContextSource.class).hasBean("ragContextSource")
                            .hasSingleBean(MemoryStore.class);
                    assertThat(context.getBean("ragContextSource")).isSameAs(context.getBean("agentMemoryRagContextSource"))
                            .isInstanceOf(RagContextSource.class);
                });
        verifyNoInteractions(retrieval, authority);
    }

    @Test
    void optional_auto_configurations_are_discovered_without_replacing_the_legacy_entry() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(AgentFlowAutoConfiguration.class.getName(), AgentExecutionAutoConfiguration.class.getName(),
                        AgentMemoryJdbcAutoConfiguration.class.getName(), AgentMemoryRagAutoConfiguration.class.getName());
    }
}
