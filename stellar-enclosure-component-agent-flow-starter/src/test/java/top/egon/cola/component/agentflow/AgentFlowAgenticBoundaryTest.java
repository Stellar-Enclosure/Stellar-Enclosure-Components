package top.egon.cola.component.agentflow;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler.LeafOutcomeVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.DependencyRefVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LeafVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.MergeEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.RootTerminalVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.StrategyEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.TerminationModeEnum;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.SchemaValidator;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.TechnicalLimitsVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.TemplateVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutor;
import top.egon.cola.component.agentflow.autoconfigure.AgentExecutionAutoConfiguration;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowAutoConfiguration;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.common.exception.AgentFlowConfigurationException;
import top.egon.cola.component.agentflow.config.AgentConfigDTO;
import top.egon.cola.component.agentflow.config.AgentFlowConfigDTO;
import top.egon.cola.component.agentflow.config.AgentWorkflowConfigDTO;
import top.egon.cola.component.agentflow.config.AgentWorkflowTypeEnum;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionRequestVO;
import top.egon.cola.component.agentflow.runtime.AgentFlowFactory;
import top.egon.cola.component.agentflow.workflow.AgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.AgentWorkflowStrategyFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证停用遗留静态工作流策略后，Agentic 自主执行运行时仍可独立启动并完成编译执行。
 */
class AgentFlowAgenticBoundaryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    AgentFlowAutoConfiguration.class,
                    AgentExecutionAutoConfiguration.class))
            .withBean("researchChatModel", ChatModel.class, () -> mock(ChatModel.class))
            .withPropertyValues(
                    "egon.cola.component.agent-flow.enabled=true",
                    "egon.cola.component.agent-flow-registry.enabled=false");

    @Test
    void suppressesLegacyStaticWorkflowBeansWhenDisabledAndKeepsAgenticRuntimeOperational() {
        runner.withPropertyValues("egon.cola.component.agent-flow.legacy-static-workflow.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AgentWorkflowStrategyFactory.class);
                    assertThat(context).doesNotHaveBean(AgentWorkflowBuilderStrategy.class);
                    assertThat(context).doesNotHaveBean("agentWorkflowBuilderStrategies");
                    assertThat(context).hasSingleBean(AgentExecutor.class);
                    assertThat(context).hasSingleBean(AgenticPlanCompiler.class);
                    assertThat(context).hasSingleBean(AgentFlowFactory.class);

                    AgenticPlanCompiler compiler = context.getBean(AgenticPlanCompiler.class);
                    AgenticPlanVO plan = new AgenticPlanVO(
                            StrategyEnum.SEQUENTIAL,
                            List.of(new LeafVO("planner",
                                    new DependencyRefVO("planner-instructions", 1, "a".repeat(64)),
                                    "gpt-6-luna",
                                    List.of(new DependencyRefVO("mcp.search", 1, "b".repeat(64))))),
                            List.of(),
                            null,
                            1,
                            null,
                            MergeEnum.COLLECT_BY_AGENT,
                            List.of(),
                            new RootTerminalVO(TerminationModeEnum.ROOT_TERMINAL));
                    ExecutionInvocation invocation = mockInvocation();

                    var result = compiler.execute(plan, invocation,
                            (leaf, round, shared) -> new LeafOutcomeVO(
                                    StatusEnum.COMPLETED,
                                    Map.of("summary", "ok"),
                                    null,
                                    true,
                                    null,
                                    SideEffectOutcomeEnum.NONE),
                            (inv, path) -> {
                                throw new AssertionError("Unexpected shared value access: " + path);
                            });
                    assertThat(result.status()).isEqualTo(StatusEnum.COMPLETED);
                    assertThat(result.output()).containsKey("planner");

                    AgentFlowFactory factory = context.getBean(AgentFlowFactory.class);
                    AgentFlowConfigDTO staticComposite = new AgentFlowConfigDTO(
                            "researchChatModel",
                            "gpt-6-luna",
                            "root",
                            List.of(new AgentConfigDTO("planner", "Planner agent", "Plan", null)),
                            List.of(new AgentWorkflowConfigDTO(AgentWorkflowTypeEnum.SEQUENTIAL, "root", "Seq",
                                    List.of("planner"), null)));
                    assertThatThrownBy(() -> factory.compileRoot("legacy-flow", staticComposite,
                            Map.of("researchChatModel", Mockito.mock(ChatModel.class)), List.of()))
                            .isInstanceOf(AgentFlowConfigurationException.class)
                            .hasMessageContaining("disabled");
                });
    }

    @Test
    void registersLegacyStaticWorkflowBeansByDefaultAndMarksThemDeprecated() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentWorkflowStrategyFactory.class);
            assertThat(context).hasBean("agentWorkflowBuilderStrategies");
            assertThat(AgentWorkflowBuilderStrategy.class.getAnnotation(Deprecated.class)).isNotNull();
            assertThat(AgentWorkflowBuilderStrategy.class.getAnnotation(Deprecated.class).forRemoval()).isFalse();
            assertThat(AgentWorkflowStrategyFactory.class.getAnnotation(Deprecated.class)).isNotNull();
            assertThat(AgentWorkflowStrategyFactory.class.getAnnotation(Deprecated.class).forRemoval()).isFalse();
        });
    }

    @Test
    void bindsLegacyStaticWorkflowToggleStrictlyAndRejectsUnknownNestedKeys() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "egon.cola.component.agent-flow.enabled", "true",
                "egon.cola.component.agent-flow.legacy-static-workflow.enabled", "false")));
        AgentFlowProperties bound = binder.bind(
                "egon.cola.component.agent-flow",
                Bindable.of(AgentFlowProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseThrow(IllegalStateException::new);
        assertThat(bound.legacyStaticWorkflow().enabled()).isFalse();

        Binder invalidBinder = new Binder(new MapConfigurationPropertySource(Map.of(
                "egon.cola.component.agent-flow.legacy-static-workflow.unknown", "value")));
        assertThatThrownBy(() -> invalidBinder.bind(
                "egon.cola.component.agent-flow",
                Bindable.of(AgentFlowProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)))
                .hasStackTraceContaining("unknown");
    }

    private static ExecutionInvocation mockInvocation() {
        ExecutionInvocation invocation = mock(ExecutionInvocation.class);
        var limit = Duration.ofSeconds(10);
        var template = new TemplateVO("template", 1, "c".repeat(64), "adk", Map.of(), mock(SchemaValidator.class));
        var limits = new TechnicalLimitsVO(limit, limit, limit, limit, 16, 16, 1, 1024, 128);
        when(invocation.scope()).thenReturn(new ContextScopeVO("test", "tenant", "principal", "instance",
                "session", "execute"));
        when(invocation.deadline()).thenReturn(Instant.now().plus(limit));
        when(invocation.request()).thenReturn(new ExecutionRequestVO("exec-001", template, Map.of(), Map.of(), limits));
        return invocation;
    }
}
