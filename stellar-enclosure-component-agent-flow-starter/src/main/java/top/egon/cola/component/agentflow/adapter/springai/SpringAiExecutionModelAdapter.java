package top.egon.cola.component.agentflow.adapter.springai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;

/** Calls only the registered model, inside the execution's permit, idle timer and absolute deadline. */
@Slf4j
@Validated
public class SpringAiExecutionModelAdapter {

    private static final int MAX_FRAMES = 100_000;
    private static final int MAX_STREAM_CHARACTERS = 4_000_000;

    public record ModelRegistrationVO(String capabilityRef, String modelName, ChatModel model,
                                      int contextLimit, int outputLimit, ToIntFunction<Prompt> estimator) {

        public ModelRegistrationVO {
            ContextScopeVO.requireIdentifier(capabilityRef);
            if (modelName == null || modelName.isBlank() || modelName.length() > 255
                    || modelName.chars().anyMatch(Character::isISOControl)
                    || contextLimit < 1 || contextLimit > 1_048_576
                    || outputLimit < 1 || outputLimit > Math.min(65_536, contextLimit)) {
                throw new IllegalArgumentException("Invalid registered model");
            }
            Objects.requireNonNull(model, "registered model");
            Objects.requireNonNull(estimator, "registered model input estimator");
            if (model.getOptions() instanceof ToolCallingChatOptions tools
                    && tools.getToolCallbacks() != null && !tools.getToolCallbacks().isEmpty()) {
                throw new IllegalArgumentException("Neutral model registration cannot contain executable provider callbacks");
            }
        }

        @Override
        public String toString() {
            return "ModelRegistrationVO[redacted]";
        }
    }

    public record ModelResultVO(ChatResponse response, Integer inputTokens, Integer outputTokens) {
        @Override
        public String toString() {
            return "ModelResultVO[redacted]";
        }
    }

    public ModelResultVO generate(@NotNull ExecutionInvocation invocation, @NotNull @Valid ModelRegistrationVO model,
                                  @NotNull Prompt request) {
        return generate(invocation, model, request.getInstructions(), List.of(), null, MAX_STREAM_CHARACTERS);
    }

    public ModelResultVO generate(ExecutionInvocation invocation, ModelRegistrationVO model,
                                  List<Message> messages,
                                  List<ToolDefinition> declarations, Integer requestedOutput, int responseTextLimit) {
        var names = new HashSet<String>();
        if (declarations.stream().anyMatch(tool -> tool == null || !names.add(tool.name()))) {
            throw failure("MODEL_TOOL_DECLARATION_INVALID");
        }
        Prompt bounded = boundedPrompt(invocation, model, messages, declarations, requestedOutput);
        return invocation.modelCallWithActivity(model.capabilityRef(), activity -> {
            var stream = new StreamVO(Math.min(MAX_STREAM_CHARACTERS, responseTextLimit));
            var response = new AtomicReference<ChatResponse>();
            try {
                new MessageAggregator().aggregate(Flux.defer(() -> model.model().stream(bounded))
                        .limitRate(1).map(frame -> {
                            activity.onFrame();
                            return stream.record(frame);
                        }), response::set).blockLast();
                if (response.get() == null || response.get().getResult() == null) throw failure("MODEL_EMPTY_RESPONSE");
                stream.verifyTools(response.get(), names);
                return new ModelResultVO(response.get(), stream.inputTokens, stream.outputTokens);
            } catch (RuntimeException failed) {
                if (failed instanceof CommonException known) throw known;
                log.debug("Neutral model stream failed with type {}", failed.getClass().getSimpleName());
                throw failure("MODEL_STREAM_FAILED");
            }
        });
    }

    private static Prompt boundedPrompt(ExecutionInvocation invocation, ModelRegistrationVO model,
                                        List<Message> messages,
                                        List<ToolDefinition> declarations, Integer requestedOutput) {
        var limits = invocation.request().limits();
        int context = Math.min(model.contextLimit(), limits.contextWindowTokens());
        int output = Math.min(model.outputLimit(), limits.maxOutputTokensPerCall());
        ChatOptions defaults = model.model().getOptions();
        if (defaults != null && defaults.getMaxTokens() != null) output = Math.min(output, defaults.getMaxTokens());
        if (requestedOutput != null) output = Math.min(output, requestedOutput);
        ChatOptions.Builder<?> builder;
        if (declarations.isEmpty()) {
            builder = ChatOptions.builder();
        } else {
            builder = ToolCallingChatOptions.builder()
                    .toolCallbacks(declarations.stream().<ToolCallback>map(DeclarationOnlyTool::new).toList());
        }
        if (defaults != null) {
            builder.frequencyPenalty(defaults.getFrequencyPenalty()).presencePenalty(defaults.getPresencePenalty())
                    .stopSequences(defaults.getStopSequences()).temperature(defaults.getTemperature())
                    .topK(defaults.getTopK()).topP(defaults.getTopP());
        }
        var bounded = new Prompt(List.copyOf(messages), builder.model(model.modelName()).maxTokens(output).build());
        int input = model.estimator().applyAsInt(bounded);
        if (output < 1 || input < 0 || input > context - output) throw failure("MODEL_CONTEXT_LIMIT");
        return bounded;
    }

    private record DeclarationOnlyTool(ToolDefinition definition) implements ToolCallback {
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { throw failure("MODEL_TOOL_EXECUTION_FORBIDDEN"); }
    }

    private static final class StreamVO {
        private final int textLimit;
        private int frames;
        private long textCharacters;
        private long toolCharacters;
        private Integer inputTokens;
        private Integer outputTokens;

        private StreamVO(int textLimit) {
            if (textLimit < 1) throw failure("MODEL_STREAM_LIMIT");
            this.textLimit = textLimit;
        }

        private ChatResponse record(ChatResponse response) {
            if (++frames > MAX_FRAMES || response.getResults().size() > 1) throw failure("MODEL_STREAM_LIMIT");
            if (response.getResult() != null) {
                var message = response.getResult().getOutput();
                if (message.getText() != null) textCharacters += message.getText().length();
                for (var call : message.getToolCalls()) {
                    if (call.arguments() != null) toolCharacters += call.arguments().length();
                    if (call.name() != null) toolCharacters += call.name().length();
                    if (call.id() != null) toolCharacters += call.id().length();
                }
                if (textCharacters > textLimit || toolCharacters > MAX_STREAM_CHARACTERS) throw failure("MODEL_STREAM_LIMIT");
            }
            var metadata = response.getMetadata();
            Usage usage = metadata.getUsage();
            if (usage == null || usage instanceof EmptyUsage) return response;
            inputTokens = observe(inputTokens, usage.getPromptTokens());
            outputTokens = observe(outputTokens, usage.getCompletionTokens());
            // Spring AI's aggregator requires all usage counters. Keep optional observations separately.
            return new ChatResponse(response.getResults(), ChatResponseMetadata.builder()
                    .id(metadata.getId()).model(metadata.getModel()).build());
        }

        private void verifyTools(ChatResponse response, Set<String> allowed) {
            var ids = new HashSet<String>();
            for (var call : response.getResult().getOutput().getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id()) || !allowed.contains(call.name())) {
                    throw failure("MODEL_TOOL_UNDECLARED");
                }
            }
        }

        private static Integer observe(Integer previous, Integer value) {
            if (value != null && value < 0) throw failure("MODEL_USAGE_INVALID");
            return value == null ? previous : value;
        }
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }
}
