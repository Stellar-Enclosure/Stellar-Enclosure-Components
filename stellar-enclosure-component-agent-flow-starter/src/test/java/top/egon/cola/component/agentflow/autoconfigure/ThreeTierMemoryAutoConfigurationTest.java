package top.egon.cola.component.agentflow.autoconfigure;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.agentflow.adapter.springai.ContextAssemblyAdvisor;
import top.egon.cola.component.agentflow.adapter.springai.ContextInitAdvisor;
import top.egon.cola.component.agentflow.adapter.springai.DocMemoryAdvisor;
import top.egon.cola.component.agentflow.adapter.springai.OthersContextAdvisor;
import top.egon.cola.component.agentflow.adapter.springai.SessionHistoryAdvisor;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiContextChatClient;
import top.egon.cola.component.agentflow.adapter.springai.TextMemoryAdvisor;
import top.egon.cola.component.agentflow.api.memory.DocMemoryReader;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.TextMemoryService;
import top.egon.cola.component.agentflow.context.ContextAssemblyEngine;
import top.egon.cola.component.agentflow.memory.InMemoryDocMemoryReader;
import top.egon.cola.component.agentflow.memory.InMemorySessionChatMemoryRepository;
import top.egon.cola.component.agentflow.memory.InMemoryTextMemoryService;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeTierMemoryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    AgentFlowAutoConfiguration.class,
                    AgentExecutionAutoConfiguration.class,
                    AgentMemoryJdbcAutoConfiguration.class));

    @Test
    void doesNotRegisterThreeTierBeansWhenDisabled() {
        runner.withPropertyValues(
                        "egon.cola.component.agent-flow.enabled=true",
                        "egon.cola.component.agent-flow-registry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TurnAlignedChatMemory.class);
                    assertThat(context).doesNotHaveBean(TextMemoryService.class);
                    assertThat(context).doesNotHaveBean(DocMemoryReader.class);
                    assertThat(context).doesNotHaveBean(ContextAssemblyEngine.class);
                    assertThat(context).doesNotHaveBean(SpringAiContextChatClient.class);
                });
    }

    @Test
    void registersInMemoryThreeTierAndContextPipelineWhenEnabled() {
        runner.withPropertyValues(
                        "egon.cola.component.agent-flow.enabled=true",
                        "egon.cola.component.agent-flow-registry.enabled=false",
                        "egon.cola.component.agent-flow.memory.three-tier.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("agentTokenCountEstimator");
                    assertThat(context).getBean("threeTierSessionRepository")
                            .isInstanceOf(InMemorySessionChatMemoryRepository.class);
                    assertThat(context).getBean("turnAlignedChatMemory")
                            .isInstanceOf(TurnAlignedChatMemory.class);
                    assertThat(context).getBean("agentTextMemoryService")
                            .isInstanceOf(InMemoryTextMemoryService.class);
                    assertThat(context).getBean("agentDocMemoryReader")
                            .isInstanceOf(InMemoryDocMemoryReader.class);
                    assertThat(context).getBean("agentContextAssemblyEngine")
                            .isInstanceOf(ContextAssemblyEngine.class);
                    assertThat(context).getBean("contextInitAdvisor").isInstanceOf(ContextInitAdvisor.class);
                    assertThat(context).getBean("sessionHistoryAdvisor").isInstanceOf(SessionHistoryAdvisor.class);
                    assertThat(context).getBean("textMemoryAdvisor").isInstanceOf(TextMemoryAdvisor.class);
                    assertThat(context).getBean("docMemoryAdvisor").isInstanceOf(DocMemoryAdvisor.class);
                    assertThat(context).getBean("othersContextAdvisor").isInstanceOf(OthersContextAdvisor.class);
                    assertThat(context).getBean("contextAssemblyAdvisor").isInstanceOf(ContextAssemblyAdvisor.class);
                    assertThat(context).getBean("springAiContextChatClient")
                            .isInstanceOf(SpringAiContextChatClient.class);
                });
    }

    @Test
    void allowsCustomDocMemoryReaderAndTokenEstimatorOverride() {
        runner.withUserConfiguration(CustomSpiConfiguration.class)
                .withPropertyValues(
                        "egon.cola.component.agent-flow.enabled=true",
                        "egon.cola.component.agent-flow-registry.enabled=false",
                        "egon.cola.component.agent-flow.memory.three-tier.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("agentDocMemoryReader")).isSameAs(CustomSpiConfiguration.CUSTOM_READER);
                    assertThat(context.getBean("agentTokenCountEstimator")).isSameAs(CustomSpiConfiguration.CUSTOM_ESTIMATOR);
                });
    }

    @Test
    void failsClosedInJdbcModeWhenThreeTierTablesMissing() {
        runner.withUserConfiguration(FailingJdbcConfiguration.class)
                .withPropertyValues(
                        "egon.cola.component.agent-flow.enabled=true",
                        "egon.cola.component.agent-flow-registry.enabled=false",
                        "egon.cola.component.agent-flow.memory.mode=JDBC",
                        "egon.cola.component.agent-flow.memory.data-source-ref=memoryDataSource",
                        "egon.cola.component.agent-flow.memory.schema=egon_agent_memory",
                        "egon.cola.component.agent-flow.memory.three-tier.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSpiConfiguration {
        static final DocMemoryReader CUSTOM_READER = query -> List.of(
                new DocMemoryVO("custom-1", "Custom", "# Body", null, Instant.EPOCH));
        static final TokenCountEstimator CUSTOM_ESTIMATOR = Mockito.mock(TokenCountEstimator.class);

        @Bean("agentDocMemoryReader")
        DocMemoryReader customDocMemoryReader() {
            return CUSTOM_READER;
        }

        @Bean("agentTokenCountEstimator")
        TokenCountEstimator customTokenCountEstimator() {
            return CUSTOM_ESTIMATOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingJdbcConfiguration {
        @Bean("memoryDataSource")
        DataSource memoryDataSource() throws SQLException {
            DataSource ds = Mockito.mock(DataSource.class);
            Connection conn = Mockito.mock(Connection.class);
            Mockito.when(ds.getConnection()).thenReturn(conn);
            Mockito.when(conn.prepareStatement(Mockito.anyString())).thenThrow(new SQLException("relation does not exist"));
            return ds;
        }
    }
}
