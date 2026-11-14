package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Validator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.adapter.adk.AdkAgentExecutorAdapter;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionAdapter;
import top.egon.cola.component.agentflow.session.InMemorySessionStore;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@AutoConfiguration(after = {ValidationAutoConfiguration.class, AgentFlowAutoConfiguration.class})
@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow", name = "enabled", havingValue = "true")
public class AgentExecutionAutoConfiguration {

    @Bean("agentExecutionClock")
    @ConditionalOnMissingBean(name = "agentExecutionClock")
    public Clock agentExecutionClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "agentExecutionTimers", destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "agentExecutionTimers")
    public ScheduledExecutorService agentExecutionTimers() {
        var scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("agent-flow-deadline")
                .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean(name = {"agentSessionStore", "inMemory"})
    @ConditionalOnMissingBean(name = "agentSessionStore")
    @ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.session", name = "store-ref",
            havingValue = "inMemory", matchIfMissing = true)
    public SessionStore agentSessionStore(
            @Qualifier("agentExecutionClock") Clock clock, @Qualifier("defaultValidator") Validator validator) {
        return new InMemorySessionStore(clock, validator);
    }

    @Bean("agentContextAssembler")
    @ConditionalOnMissingBean(name = "agentContextAssembler")
    public DefaultContextAssembler agentContextAssembler(
            @Qualifier("agentExecutionClock") Clock clock, @Qualifier("defaultValidator") Validator validator) {
        return new DefaultContextAssembler(clock, validator);
    }

    @Bean("agenticPlanCompiler")
    @ConditionalOnMissingBean(name = "agenticPlanCompiler")
    public AgenticPlanCompiler agenticPlanCompiler(@Qualifier("agentExecutionClock") Clock clock) {
        return new AgenticPlanCompiler(clock);
    }

    @Bean("springAiExecutionModelAdapter")
    @ConditionalOnMissingBean(name = "springAiExecutionModelAdapter")
    public SpringAiExecutionModelAdapter springAiExecutionModelAdapter() {
        return new SpringAiExecutionModelAdapter();
    }

    @Bean("mcpToolAdapter")
    @ConditionalOnMissingBean(name = "mcpToolAdapter")
    public McpToolAdapter mcpToolAdapter() {
        return new McpToolAdapter();
    }

    @Bean("adkAgentExecutorAdapter")
    @ConditionalOnBean(name = "agentExecutionTemplateResolver")
    @ConditionalOnMissingBean(name = "adkAgentExecutorAdapter")
    public ExecutionAdapter adkAgentExecutorAdapter(
            @Qualifier("agenticPlanCompiler") AgenticPlanCompiler compiler,
            @Qualifier("springAiExecutionModelAdapter") SpringAiExecutionModelAdapter models,
            @Qualifier("mcpToolAdapter") McpToolAdapter tools,
            @Qualifier("agentExecutionTemplateResolver") AdkAgentExecutorAdapter.TemplateResolver resolver) {
        return new AdkAgentExecutorAdapter(compiler, models, tools, resolver);
    }

    @Bean("agentExecutionAdapters")
    @ConditionalOnMissingBean(name = "agentExecutionAdapters")
    public Map<String, ExecutionAdapter> agentExecutionAdapters(ListableBeanFactory beans) {
        return Map.copyOf(beans.getBeansOfType(ExecutionAdapter.class));
    }

    @Bean("agentExecutor")
    @ConditionalOnMissingBean(name = "agentExecutor")
    public DefaultAgentExecutor agentExecutor(
            @Qualifier("agentExecutionClock") Clock clock,
            @Qualifier("defaultValidator") Validator validator,
            @Qualifier("agentContextAssembler") DefaultContextAssembler assembler,
            @Qualifier("agentExecutionTimers") ScheduledExecutorService timers,
            @Qualifier("agentFlowProperties") AgentFlowProperties properties,
            @Qualifier("agentExecutionAdapters") Map<String, ExecutionAdapter> adapters,
            ListableBeanFactory beans) {
        String storeRef = properties.session().storeRef();
        String beanName = "inMemory".equals(storeRef) ? "agentSessionStore" : storeRef;
        SessionStore store = beans.getBean(beanName, SessionStore.class);
        return new DefaultAgentExecutor(clock, validator, assembler, timers, store, adapters);
    }
}
