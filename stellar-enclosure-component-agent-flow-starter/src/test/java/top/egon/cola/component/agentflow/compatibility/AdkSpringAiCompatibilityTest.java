package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static top.egon.cola.component.agentflow.compatibility.AdkCompatibilityFixtures.*;

/**
 * Readiness probes retain their original compatibility assertions and run in the normal test suite.
 */
@Timeout(10)
class AdkSpringAiCompatibilityTest {

    @Test
    void c01_native_runner_completes_a_model_call_and_records_session_output() {
        ScriptedChatModel model = new ScriptedChatModel();
        try (NativeRun run = new NativeRun(agent("single", model))) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(model.calls).hasValue(1);
            assertThat(events).anyMatch(event -> event.finalResponse()
                    && eventText(event).equals("fixture answer."));
            assertThat(run.storedSession().state()).containsEntry("single_output", "fixture answer.");
            assertThat(model.prompts.getFirst().getInstructions())
                    .anyMatch(message -> message.getText().contains("fixture question"));
        }
    }

    @Test
    void c02_generation_config_preserves_host_model_options_and_tool_context() {
        ScriptedChatModel model = new ScriptedChatModel();
        LlmRequest request = LlmRequest.builder().model(MODEL).contents(List.of(input()))
                .config(GenerateContentConfig.builder().maxOutputTokens(9).build()).build();

        bridge(model).generateContent(request, false).test().awaitDone(3, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors();

        assertThat(model.prompts.getFirst().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts.getFirst().getOptions();
        assertThat(options.getModel()).isEqualTo(MODEL);
        assertThat(options.getMaxTokens()).isEqualTo(9);
        assertThat(options.getToolContext()).containsEntry("fixture_context", "retained");
    }

    @Test
    void c03_native_tool_loop_preserves_arguments_context_and_call_response_history() {
        ScriptedChatModel model = new ScriptedChatModel();
        ProbeTool tool = new ProbeTool();
        model.response = prompt -> model.calls.get() == 1
                ? toolCall(tool.name()) : text("tool completed.", "stop", true);
        LlmAgent root = LlmAgent.builder().name("tool_agent").instruction("Use the fixture tool.")
                .model(bridge(model)).tools(tool).build();
        try (NativeRun run = new NativeRun(root)) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(model.calls).hasValue(2);
            assertThat(tool.calls).hasValue(1);
            assertThat(tool.receivedContext).isTrue();
            assertThat(((Number) tool.arguments.getFirst().get("value")).intValue()).isEqualTo(7);
            assertThat(events).anyMatch(event -> !event.functionCalls().isEmpty());
            assertThat(events).anyMatch(event -> !event.functionResponses().isEmpty());
            assertThat(model.prompts.getLast().getInstructions()).anyMatch(message ->
                    message instanceof AssistantMessage assistant && assistant.hasToolCalls());
            assertThat(model.prompts.getLast().getInstructions())
                    .as("the second model request must contain a typed tool response")
                    .anyMatch(ToolResponseMessage.class::isInstance);
            ToolResponseMessage response = (ToolResponseMessage) model.prompts.getLast().getInstructions()
                    .stream().filter(ToolResponseMessage.class::isInstance).findFirst().orElseThrow();
            assertThat(response.getResponses().getFirst().id()).isEqualTo("call_1");
            assertThat(response.getResponses().getFirst().name()).isEqualTo(tool.name());
            assertThat(response.getResponses().getFirst().responseData()).contains("receipt_1");
            assertThat(run.storedSession().state()).containsEntry("fixture_tool_receipt", "receipt_1");
        }
    }

    @Test
    void c04_tool_schema_keeps_array_items_and_enum_constraints() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        ProbeTool tool = new ProbeTool();
        LlmRequest request = LlmRequest.builder().model(MODEL).contents(List.of(input()))
                .appendTools(List.of(tool)).build();

        bridge(model).generateContent(request, false).test().awaitDone(3, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors();

        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts.getFirst().getOptions();
        ToolCallback callback = options.getToolCallbacks().getFirst();
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(callback.getToolDefinition().inputSchema());
        assertSoftly(softly -> {
            softly.assertThat(schema.at("/properties/tags/items/type").asText()).isEqualTo("string");
            softly.assertThat(schema.at("/properties/priority/enum").toString()).isEqualTo("[\"low\",\"high\"]");
        });
    }

    @Test
    void c05_native_runner_uses_streaming_model_and_delivers_text_deltas() {
        ScriptedChatModel model = new ScriptedChatModel();
        try (NativeRun run = new NativeRun(agent("stream_agent", model))) {
            List<Event> events = run.run(true).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(model.streams).hasValue(1);
            assertThat(model.calls).hasValue(0);
            assertThat(events).anyMatch(event -> event.partial().orElse(false)
                    && eventText(event).contains("hel"));
            assertSoftly(softly -> {
                softly.assertThat(events).anyMatch(event -> event.finalResponse()
                        && eventText(event).equals("hello."));
                softly.assertThat(run.storedSession().state())
                        .containsEntry("stream_agent_output", "hello.");
            });
        }
    }

    @Test
    void c06_stream_chunk_with_punctuation_is_not_final_before_finish_reason() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.streaming = prompt -> Flux.just(
                text("First sentence.", "", false), text(" More.", "stop", true));

        List<LlmResponse> responses = bridge(model).generateContent(request(), true)
                .toList().timeout(3, TimeUnit.SECONDS).blockingGet();

        assertThat(responses.getFirst().partial()).contains(true);
        assertThat(responses.getFirst().turnComplete()).contains(false);
    }

    @Test
    void c07_model_usage_reaches_native_agent_events() {
        ScriptedChatModel model = new ScriptedChatModel();
        try (NativeRun run = new NativeRun(agent("usage_agent", model))) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(events).anyMatch(event -> event.usageMetadata()
                    .map(usage -> usage.totalTokenCount().orElse(0) == 13
                            && usage.promptTokenCount().orElse(0) == 10
                            && usage.candidatesTokenCount().orElse(0) == 3).orElse(false));
        }
    }

    @Test
    void c08_usage_only_stream_frame_retains_usage_without_fabricating_text() {
        ScriptedChatModel model = new ScriptedChatModel();
        ChatResponse usageOnly = new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, 3, 13)).build());
        model.streaming = prompt -> Flux.just(text("answer.", "stop", false), usageOnly);

        List<LlmResponse> responses = bridge(model).generateContent(request(), true)
                .toList().timeout(3, TimeUnit.SECONDS).blockingGet();

        assertThat(responses).anyMatch(response -> response.usageMetadata()
                .map(usage -> usage.totalTokenCount().orElse(0) == 13).orElse(false));
        assertThat(responses.getLast().content()).isEmpty();
    }

    @Test
    void c09_model_stream_error_is_observable_after_a_delta() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.streaming = prompt -> Flux.concat(
                Flux.just(text("partial", "", false)),
                Flux.error(new IllegalStateException("fixture_stream_failure")));
        try (NativeRun run = new NativeRun(agent("error_agent", model))) {
            TestSubscriber<Event> subscriber = run.run(true).test().awaitDone(3, TimeUnit.SECONDS);

            subscriber.assertError(error -> error.getMessage() != null
                    && error.getMessage().contains("fixture_stream_failure"));
            assertThat(subscriber.values()).anyMatch(event -> eventText(event).contains("partial"));
        }
    }

    @Test
    void c10_direct_bridge_cancel_reaches_model_subscription() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        model.streaming = prompt -> source.asFlux();
        TestSubscriber<LlmResponse> subscriber = bridge(model).generateContent(request(), true).test();
        try {
            assertThat(model.streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            subscriber.cancel();
            assertThat(model.streamCancelled.await(250, TimeUnit.MILLISECONDS))
                    .as("cancelling the ADK bridge must cancel its Spring AI subscription").isTrue();
        } finally {
            subscriber.cancel();
            source.tryEmitComplete();
        }
    }

    @Test
    void c11_native_runner_cancel_reaches_model_subscription() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        model.streaming = prompt -> source.asFlux();
        try (NativeRun run = new NativeRun(agent("cancel_agent", model))) {
            TestSubscriber<Event> subscriber = run.run(true).test();
            try {
                assertThat(model.streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
                subscriber.cancel();
                assertThat(model.streamCancelled.await(250, TimeUnit.MILLISECONDS))
                        .as("cancelling Runner must reach the model stream").isTrue();
            } finally {
                subscriber.cancel();
                source.tryEmitComplete();
            }
        }
    }

    @Test
    void c12_bridge_does_not_request_unbounded_model_data_without_downstream_demand() {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicLong requested = new AtomicLong();
        AtomicInteger produced = new AtomicInteger();
        model.streaming = prompt -> Flux.range(0, 64)
                .doOnRequest(value -> requested.accumulateAndGet(value, Math::max))
                .map(index -> {
                    produced.incrementAndGet();
                    return text("chunk", "", false);
                });
        TestSubscriber<LlmResponse> subscriber = new TestSubscriber<>(0L);
        try {
            bridge(model).generateContent(request(), true).subscribe(subscriber);
            subscriber.assertNoValues();
            assertThat(requested.get()).as("model demand must be bounded, produced=%s", produced.get())
                    .isLessThan(Long.MAX_VALUE);
        } finally {
            subscriber.cancel();
        }
    }

    @Test
    void c13_host_tool_callback_is_executable_through_the_existing_model_only_binding() {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger calls = new AtomicInteger();
        ToolCallback hostTool = FunctionToolCallback.builder("host_lookup",
                        (java.util.Map<String, Object> arguments) -> {
                            calls.incrementAndGet();
                            return "host_result";
                        }).description("Read a fixture.").inputType(java.util.Map.class).build();
        model.options = ToolCallingChatOptions.builder().model(MODEL).toolCallbacks(hostTool).build();
        model.response = prompt -> model.calls.get() == 1
                ? toolCall("host_lookup") : text("host tool completed.", "stop", true);

        try (NativeRun run = new NativeRun(agent("host_tool_agent", model))) {
            TestSubscriber<Event> subscriber = run.run(false).test().awaitDone(3, TimeUnit.SECONDS);
            assertSoftly(softly -> {
                softly.assertThat(calls)
                        .as("a callback only attached to ChatModel options must not be assumed wired to ADK")
                        .hasValue(1);
                softly.assertThatCode(() -> subscriber.assertComplete().assertNoErrors())
                        .doesNotThrowAnyException();
            });
        }
    }

    @Test
    void c14_native_event_and_session_state_round_trip_with_both_jackson_families() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        try (NativeRun run = new NativeRun(agent("json_agent", model))) {
            Event event = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet().getLast();
            String encoded = event.toJson();
            Event restored = Event.fromJson(encoded);
            var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(encoded);
            var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build().readTree(encoded);

            assertThat(restored.id()).isEqualTo(event.id());
            assertThat(eventText(restored)).isEqualTo("fixture answer.");
            assertThat(jackson2.path("id").asText()).isEqualTo(jackson3.path("id").asText());
            assertThat(com.google.adk.sessions.Session.fromJson(run.storedSession().toJson()).state())
                    .containsEntry("json_agent_output", "fixture answer.");
        }
    }

    @Test
    void c15_streaming_tool_loop_preserves_native_tool_ownership_and_history() {
        ScriptedChatModel model = new ScriptedChatModel();
        ProbeTool tool = new ProbeTool();
        model.streaming = prompt -> Flux.just(model.streams.get() == 1
                ? toolCall(tool.name()) : text("stream tool completed.", "stop", true));
        LlmAgent root = LlmAgent.builder().name("stream_tool_agent").instruction("Use the fixture tool.")
                .model(bridge(model)).tools(tool).build();
        try (NativeRun run = new NativeRun(root)) {
            run.run(true).test().awaitDone(3, TimeUnit.SECONDS).assertComplete().assertNoErrors();

            assertThat(model.calls).hasValue(0);
            assertThat(model.streams).hasValue(2);
            assertThat(tool.calls).hasValue(1);
            assertThat(tool.receivedContext).isTrue();
            assertThat(hasToolResponse(model.prompts.getLast()))
                    .as("streamed tool responses must return to the model as tool messages").isTrue();
        }
    }

    @Test
    void c16_direct_model_cancel_positive_control_closes_the_fixture_subscription() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        model.streaming = prompt -> source.asFlux();
        var subscription = model.stream(new org.springframework.ai.chat.prompt.Prompt("fixture question"))
                .subscribe();
        try {
            assertThat(model.streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(model.streamCancelled.await(250, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            subscription.dispose();
            source.tryEmitComplete();
        }
    }
}
