package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.runtime.SpringAiModelAdapterFactory;
import top.egon.cola.component.agentflow.runtime.AgentFlowInMemoryRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Deterministic, network-free models around the ADK runner and starter's Spring AI adapter. */
final class AdkCompatibilityFixtures {

    static final String APP = "t001_compatibility";
    static final String USER = "fixture_user";
    static final String MODEL = "fixture_model";

    private AdkCompatibilityFixtures() {
    }

    static BaseLlm bridge(ChatModel model) {
        return new SpringAiModelAdapterFactory().create(model, MODEL);
    }

    static LlmAgent agent(String name, ScriptedChatModel model) {
        return LlmAgent.builder().name(name).instruction("Return the fixture result.")
                .model(bridge(model)).tools(new SpringAiModelAdapterFactory().createTools(model))
                .outputKey(name + "_output").build();
    }

    static LlmRequest request() {
        return LlmRequest.builder().model(MODEL).contents(List.of(input())).build();
    }

    static Content input() {
        return Content.builder().role("user").parts(Part.fromText("fixture question")).build();
    }

    static ChatResponse text(String content, String finishReason, boolean usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(MODEL);
        if (usage) {
            metadata.usage(new DefaultUsage(10, 3, 13));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())), metadata.build());
    }

    static ChatResponse toolCall(String name) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", name,
                        "{\"value\":7,\"tags\":[\"evidence\"],\"priority\":\"low\"}")))
                .build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    static boolean hasToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
    }

    static String eventText(Event event) {
        return event.content().flatMap(Content::parts).orElse(List.of()).stream()
                .map(Part::text).flatMap(Optional::stream).reduce("", String::concat);
    }

    static final class ScriptedChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger streams = new AtomicInteger();
        final CountDownLatch streamStarted = new CountDownLatch(1);
        final CountDownLatch streamCancelled = new CountDownLatch(1);
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatOptions options = ToolCallingChatOptions.builder()
                .model(MODEL).temperature(0.25).maxTokens(32)
                .toolContext("fixture_context", "retained").build();
        Function<Prompt, ChatResponse> response = prompt -> text("fixture answer.", "stop", true);
        Function<Prompt, Flux<ChatResponse>> streaming = prompt -> Flux.just(
                text("hel", "", false), text("lo.", "stop", true));

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() > 12) {
                throw new IllegalStateException("fixture model call bound exceeded");
            }
            prompts.add(prompt);
            return response.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                streams.incrementAndGet();
                prompts.add(prompt);
                streamStarted.countDown();
                return streaming.apply(prompt).doOnCancel(streamCancelled::countDown);
            });
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    static final class ProbeTool extends BaseTool {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean receivedContext = new AtomicBoolean();
        final CountDownLatch called = new CountDownLatch(1);
        final List<Map<String, Object>> arguments = new CopyOnWriteArrayList<>();

        ProbeTool() {
            super("lookup_fixture", "Read a deterministic fixture value.");
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                            .properties(Map.of(
                                    "value", Schema.builder().type("INTEGER").build(),
                                    "tags", Schema.builder().type("ARRAY")
                                            .items(Schema.builder().type("STRING").build()).build(),
                                    "priority", Schema.builder().type("STRING")
                                            .enum_(List.of("low", "high")).build()))
                            .required(List.of("value")).build())
                    .build());
        }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            return Single.fromCallable(() -> {
                calls.incrementAndGet();
                called.countDown();
                arguments.add(Map.copyOf(args));
                receivedContext.set(context != null);
                if (context == null) {
                    throw new IllegalStateException("fixture requires the native ADK ToolContext");
                }
                context.state().put("fixture_tool_receipt", "receipt_1");
                return Map.of("value", args.get("value"), "receipt", "receipt_1");
            });
        }
    }

    static final class NativeRun implements AutoCloseable {
        final InMemoryRunner runner;
        final Session session;

        NativeRun(BaseAgent root) {
            runner = new AgentFlowInMemoryRunner(root, APP);
            session = runner.sessionService().createSession(APP, USER)
                    .timeout(3, TimeUnit.SECONDS).blockingGet();
        }

        Flowable<Event> run(boolean stream) {
            RunConfig config = RunConfig.builder()
                    .setStreamingMode(stream ? RunConfig.StreamingMode.SSE : RunConfig.StreamingMode.NONE)
                    .setMaxLlmCalls(8).build();
            return runner.runAsync(USER, session.id(), input(), config);
        }

        Session storedSession() {
            return runner.sessionService().getSession(APP, USER, session.id(), Optional.empty())
                    .timeout(3, TimeUnit.SECONDS).blockingGet();
        }

        @Override
        public void close() {
            if (!runner.close().blockingAwait(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fixture runner did not close");
            }
        }
    }
}
