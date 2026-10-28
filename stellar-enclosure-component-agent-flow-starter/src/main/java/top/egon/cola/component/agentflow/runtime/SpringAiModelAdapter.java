package top.egon.cola.component.agentflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.common.trace.TraceContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uses ADK's message converter and Spring AI's response aggregator while preserving reactive
 * cancellation, bounded demand, and a complete final response for the native ADK agent loop.
 */
@Slf4j
final class SpringAiModelAdapter extends BaseLlm {

    private static final int MAX_STREAM_FRAMES = 100_000;
    private static final int MAX_STREAM_CHARACTERS = 4_000_000;

    private final ChatModel model;
    private final MessageConverter converter = new MessageConverter(new ObjectMapper());

    SpringAiModelAdapter(ChatModel model, String modelName) {
        super(modelName);
        this.model = model;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return Flowable.defer(() -> {
            AgentFlowInMemoryRunner.requireActive();
            Prompt prompt = converter.toLlmPrompt(request, model.getOptions());
            AgentFlowInMemoryRunner.InvocationCancellation cancellation =
                    AgentFlowInMemoryRunner.currentCancellation();
            Context otelContext = Context.current();
            TraceContext trace = cancellation == null ? TraceContext.capture() : cancellation.traceContext();
            AtomicBoolean disposed = new AtomicBoolean();
            Flowable<LlmResponse> response = stream
                    ? stream(prompt, cancellation != null)
                    : Flowable.fromPublisher(Mono.fromCallable(() -> {
                        try (Scope ignored = otelContext.makeCurrent(); TraceContext.Scope scope = trace.open()) {
                            AgentFlowInMemoryRunner.requireActive();
                            ChatResponse result = model.call(prompt);
                            return withUsage(converter.toLlmResponse(result), usage(result));
                        } catch (RuntimeException failure) {
                            if (disposed.get() || (cancellation != null && cancellation.isCancelled())) {
                                log.debug("Agent Flow model stage=CALL outcome=CANCELLED errorType={}",
                                        failure.getClass().getSimpleName());
                                return null;
                            }
                            throw failure;
                        }
                    }).subscribeOn(Schedulers.boundedElastic()).doOnCancel(() -> disposed.set(true)));
            return cancellation == null ? response : response.takeUntil(cancellation.signal().toFlowable());
        });
    }

    private Flowable<LlmResponse> stream(Prompt prompt, boolean nativeRunner) {
        return Flowable.fromPublisher(Flux.defer(() -> {
            StreamState state = new StreamState();
            MessageAggregator aggregator = new MessageAggregator();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Flux<ChatResponse> source = Flux.defer(() -> model.stream(prompt))
                    .limitRate(1)
                    .map(state::record)
                    .onErrorResume(error -> {
                        failure.set(error);
                        return Flux.empty();
                    });
            Flux<LlmResponse> partials = aggregator.aggregate(source, state.aggregated::set)
                    .<LlmResponse>handle((response, sink) -> {
                        if (response.getResult() == null || response.hasToolCalls()) {
                            return;
                        }
                        String text = response.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            sink.next(converter.toLlmResponse(response, true).toBuilder()
                                    .partial(true).turnComplete(false).usageMetadata(null).build());
                        }
                    });
            return partials.concatWith(Flux.defer(() -> failure.get() == null
                    ? Flux.fromIterable(state.finish(converter, nativeRunner))
                    : Flux.error(failure.get())));
        }));
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Live connection is not supported for Spring AI models");
    }

    private static LlmResponse withUsage(LlmResponse response, Usage usage) {
        return response.toBuilder().usageMetadata(toAdkUsage(usage)).build();
    }

    private static Usage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        return usage instanceof EmptyUsage ? null : usage;
    }

    private static GenerateContentResponseUsageMetadata toAdkUsage(Usage usage) {
        if (usage == null) {
            return null;
        }
        var builder = GenerateContentResponseUsageMetadata.builder();
        if (usage.getPromptTokens() != null) {
            builder.promptTokenCount(nonNegative(usage.getPromptTokens()));
        }
        if (usage.getCompletionTokens() != null) {
            builder.candidatesTokenCount(nonNegative(usage.getCompletionTokens()));
        }
        if (usage.getTotalTokens() != null) {
            builder.totalTokenCount(nonNegative(usage.getTotalTokens()));
        }
        return builder.build();
    }

    private static int nonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("model usage must not be negative");
        }
        return value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static final class StreamState {
        private final AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        private int frames;
        private int characters;
        private Usage lastUsage;
        private boolean lastFrameUsageOnly;

        private ChatResponse record(ChatResponse response) {
            if (++frames > MAX_STREAM_FRAMES) {
                throw new IllegalStateException("model stream exceeded the frame limit");
            }
            if (response.getResult() != null) {
                AssistantMessage message = response.getResult().getOutput();
                if (message.getText() != null) {
                    characters += message.getText().length();
                }
                for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                    if (call.arguments() != null) {
                        characters += call.arguments().length();
                    }
                }
                if (characters > MAX_STREAM_CHARACTERS) {
                    throw new IllegalStateException("model stream exceeded the response size limit");
                }
            }
            Usage current = usage(response);
            lastFrameUsageOnly = response.getResult() == null && current != null;
            if (current == null) {
                return response;
            }
            lastUsage = current;
            if (current.getPromptTokens() != null && current.getCompletionTokens() != null
                    && current.getTotalTokens() != null) {
                return response;
            }
            // MessageAggregator expects non-null counters. Keep the original usage separately.
            ChatResponseMetadata metadata = response.getMetadata();
            Map<String, Object> entries = new LinkedHashMap<>();
            metadata.entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
            ChatResponseMetadata safeMetadata = ChatResponseMetadata.builder().metadata(entries)
                    .id(metadata.getId()).model(metadata.getModel()).rateLimit(metadata.getRateLimit())
                    .promptMetadata(metadata.getPromptMetadata())
                    .usage(new DefaultUsage(orZero(current.getPromptTokens()), orZero(current.getCompletionTokens()),
                            orZero(current.getTotalTokens())))
                    .build();
            return new ChatResponse(response.getResults(), safeMetadata);
        }

        private List<LlmResponse> finish(MessageConverter converter, boolean nativeRunner) {
            ChatResponse result = aggregated.get();
            if (result == null || result.getResult() == null) {
                return lastUsage == null ? List.of() : List.of(LlmResponse.builder()
                        .usageMetadata(toAdkUsage(lastUsage)).build());
            }
            AssistantMessage message = result.getResult().getOutput();
            Set<String> toolIds = new HashSet<>();
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || !toolIds.add(call.id())) {
                    throw new IllegalStateException("model stream returned missing or duplicate tool call ids");
                }
            }
            List<LlmResponse> responses = new ArrayList<>();
            if ((message.getText() != null && !message.getText().isEmpty()) || !message.getToolCalls().isEmpty()) {
                responses.add(withUsage(converter.toLlmResponse(result), lastUsage));
            }
            // A native ADK invocation receives usage on its final response. An additional
            // metadata-only event after a function call could end the native loop prematurely.
            if (lastUsage != null && (responses.isEmpty() || (lastFrameUsageOnly && !nativeRunner))) {
                responses.add(LlmResponse.builder().usageMetadata(toAdkUsage(lastUsage)).build());
            }
            return responses;
        }
    }
}
