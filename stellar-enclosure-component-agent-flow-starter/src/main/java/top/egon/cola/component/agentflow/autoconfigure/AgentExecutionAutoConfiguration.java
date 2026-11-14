package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.MethodMetadata;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.memory.MemoryStore;
import top.egon.cola.component.agentflow.adapter.adk.AdkAgentExecutorAdapter;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionAdapter;
import top.egon.cola.component.agentflow.memory.InMemoryMemoryStore;
import top.egon.cola.component.agentflow.session.InMemorySessionStore;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Slf4j
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

    @Bean("agentMemoryStore")
    @ConditionalOnMissingBean(MemoryStore.class)
    @ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.memory", name = "mode",
            havingValue = AgentFlowProperties.MemorySettings.IN_MEMORY, matchIfMissing = true)
    public MemoryStore agentInMemoryStore(
            @Qualifier("agentExecutionClock") Clock clock, @Qualifier("defaultValidator") Validator validator) {
        return new InMemoryMemoryStore(clock, validator);
    }

    @Bean("agentMemoryConfigurationGuard")
    public SmartInitializingSingleton agentMemoryConfigurationGuard(
            @Qualifier("agentFlowProperties") AgentFlowProperties properties, ConfigurableListableBeanFactory beans) {
        return () -> {
            if (AgentFlowProperties.MemorySettings.JDBC.equals(properties.memory().mode())
                    && !configuredBean(beans, "jdbcMemoryStore", MemoryStore.class, "AgentMemoryJdbcAutoConfiguration", "agentJdbcMemoryStore")) {
                throw new IllegalStateException("Agent Flow JDBC memory requires Spring JDBC, the PostgreSQL driver and the selected DataSource");
            }
            if (properties.rag().enabled()
                    && !configuredBean(beans, "ragContextSource", ContextSource.class, "AgentMemoryRagAutoConfiguration", "agentMemoryRagContextSource")) {
                throw new IllegalStateException("Agent Flow RAG requires the optional RAG adapter and explicit host authority");
            }
        };
    }

    private static boolean configuredBean(ConfigurableListableBeanFactory beans, String name, Class<?> type,
                                          String configuration, String factoryMethod) {
        if (!beans.containsBeanDefinition(name)
                || !(beans.getBeanDefinition(name) instanceof AnnotatedBeanDefinition definition)) return false;
        MethodMetadata source = definition.getFactoryMethodMetadata();
        return source != null && source.getDeclaringClassName().equals(AgentExecutionAutoConfiguration.class.getPackageName() + "." + configuration)
                && factoryMethod.equals(source.getMethodName()) && beans.isTypeMatch(name, type);
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
