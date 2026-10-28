package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.runtime.AgentFlowSessionRunner;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static top.egon.cola.component.agentflow.compatibility.AdkCompatibilityFixtures.*;

@Timeout(10)
class AgentFlowSessionRunnerTest {
    @Test
    void supplied_session_service_survives_runner_replacement_and_supplies_prior_history() {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER, java.util.Map.of(), "shared-session").blockingGet();
        var firstModel = new ScriptedChatModel();
        AtomicInteger governedCalls = new AtomicInteger();
        com.google.adk.plugins.Plugin plugin = new com.google.adk.plugins.Plugin() {
            @Override public String getName() { return "fixture-governance"; }
            @Override public io.reactivex.rxjava3.core.Maybe<LlmResponse> beforeModelCallback(
                    com.google.adk.agents.CallbackContext context, com.google.adk.models.LlmRequest.Builder request) {
                governedCalls.incrementAndGet();
                return io.reactivex.rxjava3.core.Maybe.empty();
            }
        };
        var first = new AgentFlowSessionRunner(agent("assistant", firstModel), APP, sessions, List.of(plugin));
        try {
            assertThat(first.sessionService()).isSameAs(sessions);
            first.runAsync(USER, session.id(), Content.fromParts(Part.fromText("first question"))).toList().blockingGet();
            assertThat(governedCalls).hasValue(1);
        } finally {
            first.close().blockingAwait();
        }
        var secondModel = new ScriptedChatModel();
        var second = new AgentFlowSessionRunner(agent("assistant", secondModel), APP, sessions, List.of());
        try {
            second.runAsync(USER, session.id(), Content.fromParts(Part.fromText("second question"))).toList().blockingGet();
            assertThat(secondModel.prompts).hasSize(1);
            assertThat(secondModel.prompts.getFirst().getInstructions().stream().map(message -> message.getText()).toList())
                    .contains("first question", "fixture answer.", "second question");
        } finally {
            second.close().blockingAwait();
        }
    }

    @Test
    void supplied_session_runner_propagates_trace_and_cancels_the_model_subscription() throws InterruptedException {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER).blockingGet();
        var model = new ScriptedChatModel();
        AtomicReference<String> tenant = new AtomicReference<>();
        model.streaming = prompt -> Flux.defer(() -> {
            tenant.set(MDC.get("tenantId"));
            return Flux.<ChatResponse>never();
        });
        var runner = new AgentFlowSessionRunner(agent("assistant", model), APP, sessions, List.of());
        MDC.put("tenantId", "tenant-101");
        TestSubscriber<Event> subscriber = runner.runAsync(USER, session.id(), input(),
                RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build()).test();
        try {
            assertThat(model.streamStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(tenant).hasValue("tenant-101");
            subscriber.cancel();
            assertThat(model.streamCancelled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(MDC.get("tenantId")).isEqualTo("tenant-101");
        } finally {
            subscriber.cancel();
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
            MDC.remove("tenantId");
        }
    }
}
