package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.memory.jdbc.JdbcMemoryStore;
import top.egon.cola.component.agentflow.memory.jdbc.MemoryStoreStorageVerifier;

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
        var configured = properties.memory();
        DataSource selected;
        try {
            selected = beans.getBean(configured.dataSourceRef(), DataSource.class);
        } catch (BeansException unavailable) {
            throw new IllegalStateException("Agent Flow JDBC memory requires the configured DataSource '" + configured.dataSourceRef() + "'");
        }
        new MemoryStoreStorageVerifier(selected, configured.schema()).verify();
        return new JdbcMemoryStore(selected, configured.schema(), "COMPONENT", clock, validator);
    }

}
