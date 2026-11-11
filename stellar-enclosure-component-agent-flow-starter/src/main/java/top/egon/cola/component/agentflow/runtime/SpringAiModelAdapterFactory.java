package top.egon.cola.component.agentflow.runtime;

import com.google.adk.models.BaseLlm;
import com.google.adk.tools.BaseTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import top.egon.cola.component.agentflow.common.exception.AgentFlowConfigurationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adapts a host-provided Spring AI model and its declared tools to ADK. */
@Slf4j
public class SpringAiModelAdapterFactory {

    public BaseLlm create(ChatModel chatModel, String modelName) {
        if (chatModel == null) {
            throw new AgentFlowConfigurationException("properties", "chatModel", "ChatModel bean is required");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new AgentFlowConfigurationException("properties", "modelName", "model name is required");
        }
        try {
            return new SpringAiModelAdapter(chatModel, modelName.trim());
        } catch (RuntimeException failure) {
            throw new AgentFlowConfigurationException("properties", "modelName", "Spring AI adapter creation failed", failure);
        }
    }

    /** Checks the compiled adapter's exact ADK name and Spring AI model instance. */
    public static boolean matchesCompiledModel(BaseLlm adapter, String expectedModelName, ChatModel expectedModel) {
        return adapter instanceof SpringAiModelAdapter springAiAdapter
                && expectedModelName != null && expectedModelName.equals(springAiAdapter.model())
                && springAiAdapter.usesChatModel(expectedModel);
    }

    public List<BaseTool> createTools(ChatModel chatModel) {
        if (chatModel == null) {
            throw new AgentFlowConfigurationException("properties", "chatModel", "ChatModel bean is required");
        }
        if (!(chatModel.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null) {
            return List.of();
        }
        List<BaseTool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : options.getToolCallbacks()) {
            SpringAiToolAdapter tool = new SpringAiToolAdapter(callback, options.getToolContext());
            if (!names.add(tool.name())) {
                throw new AgentFlowConfigurationException("properties", tool.name(), "duplicate tool name");
            }
            tools.add(tool);
        }
        return List.copyOf(tools);
    }
}
