package top.egon.cola.component.agentflow.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.springai.ToolConverter;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import org.springframework.ai.tool.ToolCallback;
import top.egon.cola.component.agentflow.common.exception.AgentFlowConfigurationException;
import top.egon.cola.component.common.trace.TraceContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Makes a host ToolCallback visible to ADK so the native agent loop executes it. */
final class SpringAiToolAdapter extends BaseTool {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION, false);
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() { };

    private final ToolCallback callback;
    private final FunctionDeclaration declaration;
    private final Map<String, Object> hostContext;

    SpringAiToolAdapter(ToolCallback callback, Map<String, Object> hostContext) {
        super(name(callback), description(callback));
        this.callback = callback;
        this.hostContext = hostContext == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(hostContext));
        this.declaration = FunctionDeclaration.builder().name(name()).description(description())
                .parametersJsonSchema(schema(callback)).build();
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> arguments, ToolContext context) {
        return Single.fromCallable(() -> {
            AgentFlowInMemoryRunner.requireActive();
            Objects.requireNonNull(context, "ADK tool context is required");
            Map<String, Object> values = new LinkedHashMap<>(hostContext);
            // MCP callbacks may send ToolContext entries as remote metadata. Never send an ADK
            // invocation or session object through the host's tool boundary.
            values.remove(ToolConverter.ADK_TOOL_CONTEXT_KEY);
            AgentFlowInMemoryRunner.InvocationCancellation invocation = AgentFlowInMemoryRunner.currentCancellation();
            String result;
            if (invocation == null) {
                result = callback.call(JSON.writeValueAsString(arguments),
                        new org.springframework.ai.chat.model.ToolContext(values));
            } else {
                try (TraceContext.Scope ignored = invocation.traceContext().open()) {
                    AgentFlowInMemoryRunner.requireActive();
                    result = callback.call(JSON.writeValueAsString(arguments),
                            new org.springframework.ai.chat.model.ToolContext(values));
                }
            }
            if (callback.getToolMetadata().returnDirect()) {
                context.actions().setSkipSummarization(true);
            }
            return Map.of("result", Objects.requireNonNull(result, "tool returned null"));
        });
    }

    private static String name(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null
                || callback.getToolDefinition().name() == null
                || callback.getToolDefinition().name().isBlank()) {
            throw new AgentFlowConfigurationException("properties", "tool", "tool name is required");
        }
        return callback.getToolDefinition().name();
    }

    private static String description(ToolCallback callback) {
        return Objects.requireNonNullElse(callback.getToolDefinition().description(), "");
    }

    private static Map<String, Object> schema(ToolCallback callback) {
        try {
            String value = callback.getToolDefinition().inputSchema();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("tool input schema is required");
            }
            Map<String, Object> schema = JSON.readValue(value, OBJECT_TYPE);
            if (schema == null) {
                throw new IllegalArgumentException("tool input schema must be an object");
            }
            return schema;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new AgentFlowConfigurationException("properties", name(callback),
                    "tool input schema is invalid", failure);
        }
    }
}
