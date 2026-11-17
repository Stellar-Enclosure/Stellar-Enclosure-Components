package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import top.egon.cola.component.agentflow.api.memory.DocMemoryReader;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.api.memory.TextMemoryService;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.agentflow.memory.jdbc.JdbcDocMemoryReader;
import top.egon.cola.component.agentflow.memory.jdbc.JdbcMemoryStore;
import top.egon.cola.component.agentflow.memory.jdbc.JdbcSessionChatMemoryRepository;
import top.egon.cola.component.agentflow.memory.jdbc.JdbcTextMemoryService;
import top.egon.cola.component.agentflow.memory.jdbc.MemoryStoreStorageVerifier;
import top.egon.cola.component.agentflow.memory.jdbc.ThreeTierMemoryStorageVerifier;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import javax.sql.DataSource;
import java.time.Clock;

@Slf4j
@AutoConfiguration(after = {AgentFlowAutoConfiguration.class, AgentExecutionAutoConfiguration.class})
@ConditionalOnClass(name = {"org.springframework.jdbc.core.JdbcTemplate", "org.postgresql.Driver"})
@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.memory", name = "mode", havingValue = "JDBC")
public class AgentMemoryJdbcAutoConfiguration {

    @Bean(name = {"jdbcMemoryStore", "agentMemoryStore"})
    public MemoryStore agentJdbcMemoryStore(
            @Qualifier("agentFlowProperties") AgentFlowProperties properties,
            @Qualifier("agentExecutionClock") Clock clock,
            @Qualifier("defaultValidator") Validator validator,
            ListableBeanFactory beans) {
        AgentFlowProperties.MemorySettings configured = properties.memory();
        DataSource selected = resolveDataSource(configured, beans);
        new MemoryStoreStorageVerifier(selected, configured.schema()).verify();
        return new JdbcMemoryStore(selected, configured.schema(), "COMPONENT", clock, validator);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.memory.three-tier",
            name = "enabled", havingValue = "true")
    static class ThreeTierJdbcConfiguration {

        @Bean("threeTierMemoryStorageVerifier")
        @ConditionalOnMissingBean(name = "threeTierMemoryStorageVerifier")
        ThreeTierMemoryStorageVerifier threeTierMemoryStorageVerifier(
                @Qualifier("agentFlowProperties") AgentFlowProperties properties,
                ListableBeanFactory beans) {
            AgentFlowProperties.MemorySettings configured = properties.memory();
            DataSource selected = resolveDataSource(configured, beans);
            boolean verifyDocTable = beans.getBeanNamesForType(DocMemoryReader.class).length == 0;
            var verifier = new ThreeTierMemoryStorageVerifier(
                    selected, configured.schema(), configured.doc().tableName(), verifyDocTable);
            verifier.verify();
            return verifier;
        }

        @Bean("threeTierSessionRepository")
        @ConditionalOnMissingBean(name = "threeTierSessionRepository")
        @DependsOn("threeTierMemoryStorageVerifier")
        TurnAlignedChatMemory.TurnAwareChatMemoryRepository threeTierSessionRepository(
                @Qualifier("agentFlowProperties") AgentFlowProperties properties,
                @Qualifier("agentExecutionClock") Clock clock,
                ListableBeanFactory beans) {
            AgentFlowProperties.MemorySettings configured = properties.memory();
            return new JdbcSessionChatMemoryRepository(
                    resolveDataSource(configured, beans), configured.schema(), clock);
        }

        @Bean("agentTextMemoryService")
        @ConditionalOnMissingBean(name = "agentTextMemoryService")
        @DependsOn("threeTierMemoryStorageVerifier")
        TextMemoryService agentTextMemoryService(
                @Qualifier("agentFlowProperties") AgentFlowProperties properties,
                @Qualifier("agentExecutionClock") Clock clock,
                @Qualifier("egonColaValidationUtils") ValidationUtils validationUtils,
                ListableBeanFactory beans) {
            AgentFlowProperties.MemorySettings configured = properties.memory();
            return new JdbcTextMemoryService(
                    resolveDataSource(configured, beans),
                    configured.schema(),
                    configured.textMaxChars(),
                    clock,
                    validationUtils);
        }

        @Bean("agentDocMemoryReader")
        @ConditionalOnMissingBean(DocMemoryReader.class)
        @DependsOn("threeTierMemoryStorageVerifier")
        DocMemoryReader agentDocMemoryReader(
                @Qualifier("agentFlowProperties") AgentFlowProperties properties,
                @Qualifier("egonColaValidationUtils") ValidationUtils validationUtils,
                ListableBeanFactory beans) {
            AgentFlowProperties.MemorySettings configured = properties.memory();
            return new JdbcDocMemoryReader(
                    resolveDataSource(configured, beans),
                    configured.schema(),
                    configured.doc(),
                    validationUtils);
        }
    }

    private static DataSource resolveDataSource(AgentFlowProperties.MemorySettings configured,
                                                ListableBeanFactory beans) {
        try {
            return beans.getBean(configured.dataSourceRef(), DataSource.class);
        } catch (BeansException unavailable) {
            throw new IllegalStateException(
                    "Agent Flow JDBC memory requires the configured DataSource '" + configured.dataSourceRef() + "'");
        }
    }
}
