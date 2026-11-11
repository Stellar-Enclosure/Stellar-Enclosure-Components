package top.egon.cola.component.agentflow.runtime;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.Model;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.config.AgentConfigDTO;
import top.egon.cola.component.agentflow.config.AgentFlowConfigDTO;
import top.egon.cola.component.agentflow.config.AgentWorkflowConfigDTO;
import top.egon.cola.component.agentflow.config.AgentWorkflowTypeEnum;
import top.egon.cola.component.agentflow.common.exception.AgentFlowConfigurationException;
import top.egon.cola.component.agentflow.workflow.LoopAgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.ParallelAgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.SequentialAgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.AgentWorkflowStrategyFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class AgentFlowFactoryTest {

    private final FakeChatModel chatModel = new FakeChatModel();
    private final AgentFlowFactory factory = new AgentFlowFactory(
            new SpringAiModelAdapterFactory(),
            new AgentWorkflowStrategyFactory(List.of(
                    new SequentialAgentWorkflowBuilderStrategy(),
                    new ParallelAgentWorkflowBuilderStrategy(),
                    new LoopAgentWorkflowBuilderStrategy())));

    @Test
    void builds_leaf_and_three_workflow_root_types() {
        for (AgentFlowConfigDTO config : List.of(
                leafFlow(), compositeFlow(AgentWorkflowTypeEnum.SEQUENTIAL),
                compositeFlow(AgentWorkflowTypeEnum.PARALLEL),
                compositeFlow(AgentWorkflowTypeEnum.LOOP))) {
            AgentFlowFactory.AgentFlowCompilationBO compilation = factory.create(
                    "research-flow", config, Map.of("researchChatModel", chatModel));
            try {
                if (config.workflows().isEmpty()) {
                    assertThat(compilation.rootAgent()).isInstanceOf(LlmAgent.class);
                } else {
                    assertThat(compilation.rootAgent()).isInstanceOf(switch (config.workflows().getFirst().type()) {
                        case SEQUENTIAL -> SequentialAgent.class;
                        case PARALLEL -> ParallelAgent.class;
                        case LOOP -> LoopAgent.class;
                    });
                }
                assertThat(compilation.runner().appName()).isEqualTo("research-flow");
            } finally {
                compilation.runner().close().blockingAwait();
            }
        }
        assertThat(chatModel.calls).isZero();
    }

    @Test
    void compiles_root_without_owning_a_runner_and_uses_the_explicit_tool_list() {
        var root = factory.compileRoot("research-flow", leafFlow(), Map.of("researchChatModel", chatModel), List.of());
        assertThat(root).isInstanceOf(LlmAgent.class);
        assertThat(((LlmAgent) root).tools().blockingGet()).isEmpty();
        assertThat(chatModel.calls).isZero();
    }

    @Test
    void matches_only_the_exact_compiled_adapter_name_and_model_instance() {
        var options = ChatOptions.builder().model("model-name").maxTokens(64).build();
        var expected = new EquivalentChatModel(options);
        var equivalent = new EquivalentChatModel(options);
        assertThat(equivalent).isEqualTo(expected).isNotSameAs(expected);

        var root = (LlmAgent) factory.compileRoot("research-flow", leafFlow(),
                Map.of("researchChatModel", expected), List.of());
        var compiled = root.model().flatMap(Model::model).orElseThrow();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, "model-name", expected)).isTrue();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, "model-name", equivalent)).isFalse();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, "other-model", expected)).isFalse();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, "model-name ", expected)).isFalse();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, null, expected)).isFalse();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(compiled, "model-name", null)).isFalse();
    }

    @Test
    void rejects_another_adk_adapter_with_the_same_model_name() {
        BaseLlm foreign = new BaseLlm("model-name") {
            @Override
            public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
                throw new AssertionError("Adapter generation must not run during identity verification");
            }

            @Override
            public BaseLlmConnection connect(LlmRequest request) {
                throw new AssertionError("Adapter connection must not run during identity verification");
            }
        };

        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(foreign, "model-name", chatModel)).isFalse();
        assertThat(SpringAiModelAdapterFactory.matchesCompiledModel(null, "model-name", chatModel)).isFalse();
        assertThat(chatModel.calls).isZero();
    }

    @Test
    void rejects_missing_named_chat_model_without_provider_call() {
        assertThatThrownBy(() -> factory.create("research-flow", leafFlow(), Map.of()))
                .isInstanceOf(AgentFlowConfigurationException.class)
                .hasMessageContaining("researchChatModel");
        assertThat(chatModel.calls).isZero();
    }

    @Test
    void wraps_adk_builder_failure_without_exposing_instruction() {
        String instruction = "secret instruction must not appear in this error";
        AgentConfigDTO invalidAgent = new AgentConfigDTO("bad/name", "description", instruction, null);
        AgentFlowConfigDTO invalid = new AgentFlowConfigDTO(
                "researchChatModel", "model-name", "bad/name", List.of(invalidAgent), List.of());

        assertThatThrownBy(() -> factory.create("research-flow", invalid, Map.of("researchChatModel", chatModel)))
                .isInstanceOf(AgentFlowConfigurationException.class)
                .hasMessageNotContaining(instruction);
    }

    private static AgentFlowConfigDTO leafFlow() {
        return new AgentFlowConfigDTO("researchChatModel", "model-name", "planner",
                List.of(new AgentConfigDTO("planner", "planner description", "Plan the work", "plan")), List.of());
    }

    private static AgentFlowConfigDTO compositeFlow(AgentWorkflowTypeEnum type) {
        String root = type.name().toLowerCase();
        return new AgentFlowConfigDTO("researchChatModel", "model-name", root,
                List.of(
                        new AgentConfigDTO("first", "first description", "Run first", "first-output"),
                        new AgentConfigDTO("second", "second description", "Run second", "second-output")),
                List.of(new AgentWorkflowConfigDTO(type, root, root + " description",
                        List.of("first", "second"), type == AgentWorkflowTypeEnum.LOOP ? 3 : null)));
    }

    private record EquivalentChatModel(ChatOptions options) implements ChatModel {
        @Override
        public ChatOptions getOptions() {
            return options;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Model calls must not run during identity verification");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new AssertionError("Model streaming must not run during identity verification");
        }
    }

    private static final class FakeChatModel implements ChatModel {
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            return null;
        }
    }
}
