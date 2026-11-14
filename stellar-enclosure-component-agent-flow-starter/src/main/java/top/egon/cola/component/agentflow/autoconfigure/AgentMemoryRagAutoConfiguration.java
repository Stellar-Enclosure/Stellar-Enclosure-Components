package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.integration.rag.RagContextSource;
import top.egon.cola.component.rag.api.RagRetrievalService;

import java.time.Clock;

@Slf4j
@AutoConfiguration(after = {AgentFlowAutoConfiguration.class, AgentExecutionAutoConfiguration.class},
        afterName = "top.egon.cola.component.rag.autoconfigure.RagAutoConfiguration")
@ConditionalOnClass(name = "top.egon.cola.component.rag.api.RagRetrievalService")
@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.rag", name = "enabled", havingValue = "true")
public class AgentMemoryRagAutoConfiguration {
    @Bean(name = {"ragContextSource", "agentMemoryRagContextSource"})
    public ContextSource agentMemoryRagContextSource(
            @Qualifier("ragRetrievalService") RagRetrievalService retrieval,
            @Qualifier("agentMemoryRagAuthority") RagContextSource.Authority authority,
            @Qualifier("agentExecutionClock") Clock clock,
            @Qualifier("defaultValidator") Validator validator) {
        return new RagContextSource(retrieval, authority, clock, validator);
    }

}
