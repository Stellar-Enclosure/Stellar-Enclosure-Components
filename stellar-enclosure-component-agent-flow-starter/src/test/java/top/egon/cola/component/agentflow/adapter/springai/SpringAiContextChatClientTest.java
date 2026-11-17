package top.egon.cola.component.agentflow.adapter.springai;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.MemoryOwnerTypeEnum;
import top.egon.cola.component.agentflow.api.memory.TextMemoryBindCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemorySaveCommand;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.context.ContextAssemblyEngine;
import top.egon.cola.component.agentflow.context.ContextBudgetPolicyVO;
import top.egon.cola.component.agentflow.memory.InMemoryDocMemoryReader;
import top.egon.cola.component.agentflow.memory.InMemorySessionChatMemoryRepository;
import top.egon.cola.component.agentflow.memory.InMemoryTextMemoryService;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiContextChatClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-17T09:15:00Z"), ZoneOffset.UTC);
    private static final ContextScopeVO SCOPE = new ContextScopeVO(
            "crm", "tenant-a", "user-1", "agent-1", "sess-1", "chat");

    private record Fixture(
            TurnAlignedChatMemory chatMemory,
            InMemoryTextMemoryService textService,
            InMemoryDocMemoryReader docReader,
            SpringAiContextChatClient client) {
    }

    private static Fixture newFixture() {
        var validationUtils = new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
        var repo = new InMemorySessionChatMemoryRepository(FIXED_CLOCK);
        var chatMemory = new TurnAlignedChatMemory(repo, 20, FIXED_CLOCK);
        var textService = new InMemoryTextMemoryService(FIXED_CLOCK, validationUtils, 10_000);
        var docReader = new InMemoryDocMemoryReader(validationUtils, 32);
        var engine = new ContextAssemblyEngine(new JTokkitTokenCountEstimator());
        var properties = new AgentFlowProperties(true, null, null, java.util.Map.of());

        var client = new SpringAiContextChatClient(
                new ContextInitAdvisor(properties),
                new SessionHistoryAdvisor(chatMemory),
                new TextMemoryAdvisor(textService),
                new DocMemoryAdvisor(docReader),
                new OthersContextAdvisor(),
                new ContextAssemblyAdvisor(engine),
                validationUtils,
                properties);
        return new Fixture(chatMemory, textService, docReader, client);
    }

    @Test
    void executesFullAdvisorChainAndAppendsTurnOnSuccess() {
        Fixture f = newFixture();
        f.chatMemory().appendTurn("tenant-a", "sess-1", "turn-prev", List.of(
                new UserMessage("Prev Q"), new AssistantMessage("Prev A")));
        var bound = f.textService().bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a").namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.AGENT).ownerKey("agent-1").build());
        f.textService().save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a").id(bound.id())
                .contentText("User prefers concise SQL").expectedVersion(1L).build());
        f.docReader().put("tenant-a",
                new DocMemoryVO("d1", "Architecture", "# Clean Layers", null, FIXED_CLOCK.instant()), false);

        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel fakeModel = new FakeChatModel(prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Final Answer"))));
        }, prompt -> Flux.empty());

        var result = f.client().call(SpringAiContextChatClient.AgentContextCallBO.builder()
                .chatModel(fakeModel)
                .scope(SCOPE)
                .systemPrompt("System Rules")
                .userPrompt("New Q")
                .textMemoryId(bound.id())
                .docMemoryIds(List.of("d1"))
                .otherFragments(List.of("Extra note"))
                .budgetPolicy(new ContextBudgetPolicyVO(4096, 512, 128, 1, false))
                .build());

        assertThat(result.content()).isEqualTo("Final Answer");
        assertThat(result.report().historyTurnsIncluded()).isEqualTo(1);
        assertThat(result.report().textMemoryIncluded()).isTrue();
        assertThat(result.report().docCountIncluded()).isEqualTo(1);

        Prompt sent = capturedPrompt.get();
        assertThat(sent.getInstructions().getFirst().getText()).isEqualTo("System Rules");
        assertThat(sent.getContents())
                .contains("Prev Q")
                .contains("User prefers concise SQL")
                .contains("# Clean Layers")
                .contains("Extra note")
                .contains("New Q");

        // Level 1 must contain 2 turns and NOT pollute history with <context_data>.
        var turns = f.chatMemory().getTurns("tenant-a", "sess-1", 20);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(1).messages()).extracting(m -> m.getText())
                .containsExactly("New Q", "Final Answer");
    }

    @Test
    void doesNotAppendSessionHistoryOnModelErrorOrStreamCancel() {
        Fixture f = newFixture();

        ChatModel errorStreamModel = new FakeChatModel(
                p -> new ChatResponse(List.of(new Generation(new AssistantMessage("unused")))),
                p -> Flux.concat(
                        Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Part1"))))),
                        Flux.error(new IllegalStateException("stream exploded"))));

        var errorRequest = SpringAiContextChatClient.AgentContextCallBO.builder()
                .chatModel(errorStreamModel)
                .scope(SCOPE)
                .systemPrompt("Sys")
                .userPrompt("Hi err")
                .build();

        assertThatThrownBy(() -> f.client().stream(errorRequest, r -> {}).collectList().block())
                .hasStackTraceContaining("stream exploded");
        assertThat(f.chatMemory().get("tenant-a", "sess-1", 20)).isEmpty();

        ChatModel multiChunkModel = new FakeChatModel(
                p -> new ChatResponse(List.of(new Generation(new AssistantMessage("unused")))),
                p -> Flux.just(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("chunk1")))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("chunk2"))))));

        var cancelRequest = SpringAiContextChatClient.AgentContextCallBO.builder()
                .chatModel(multiChunkModel)
                .scope(SCOPE)
                .systemPrompt("Sys")
                .userPrompt("Hi cancel")
                .build();

        List<String> partial = f.client().stream(cancelRequest, r -> {})
                .take(1)
                .collectList()
                .block();
        assertThat(partial).containsExactly("chunk1");
        assertThat(f.chatMemory().get("tenant-a", "sess-1", 20)).isEmpty();
    }

    @Test
    void honorsCustomWindowTurnsAndPassesOrderedDocsToModel() {
        Fixture f = newFixture();
        for (int i = 1; i <= 5; i++) {
            f.chatMemory().appendTurn("tenant-a", "sess-1", "t-" + i, List.of(
                    new UserMessage("U" + i), new AssistantMessage("A" + i)));
        }
        f.docReader().put("tenant-a", new DocMemoryVO("d1", "Doc One", "MD-ONE", null, FIXED_CLOCK.instant()), false);
        f.docReader().put("tenant-a", new DocMemoryVO("d3", "Doc Three", "MD-THREE", null, FIXED_CLOCK.instant()), false);

        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel fakeModel = new FakeChatModel(prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Done"))));
        }, p -> Flux.empty());

        var result = f.client().call(SpringAiContextChatClient.AgentContextCallBO.builder()
                .chatModel(fakeModel)
                .scope(SCOPE)
                .systemPrompt("Sys")
                .userPrompt("Current")
                .windowTurns(2)
                .docMemoryIds(List.of("d3", "d1"))
                .build());

        assertThat(result.report().historyTurnsIncluded()).isEqualTo(2);
        String promptText = capturedPrompt.get().getContents();
        assertThat(promptText).doesNotContain("U3").contains("U4").contains("U5");
        assertThat(promptText.indexOf("MD-THREE")).isLessThan(promptText.indexOf("MD-ONE"));
    }

    @Test
    void executesToolCallLoopAndPersistsFullTurn() {
        Fixture f = newFixture();
        AtomicInteger callCount = new AtomicInteger();

        var toolCallback = FunctionToolCallback.builder("lookupStatus", (ToolInput in) -> "PAID:" + in.id())
                .description("Lookup order status")
                .inputType(ToolInput.class)
                .build();

        List<Prompt> seenPrompts = new ArrayList<>();
        ChatModel toolFakeModel = new FakeChatModel(prompt -> {
            seenPrompts.add(prompt);
            if (callCount.getAndIncrement() == 0) {
                var tc = new AssistantMessage.ToolCall("call-1", "function", "lookupStatus", "{\"id\":7}");
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(tc)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Status is PAID"))));
        }, p -> Flux.empty());

        var result = f.client().call(SpringAiContextChatClient.AgentContextCallBO.builder()
                .chatModel(toolFakeModel)
                .scope(SCOPE)
                .systemPrompt("Sys")
                .userPrompt("Check order 7")
                .toolCallbacks(List.of(toolCallback))
                .build());

        assertThat(result.content()).isEqualTo("Status is PAID");
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(seenPrompts.get(1).getInstructions())
                .anyMatch(m -> m instanceof ToolResponseMessage trm
                        && trm.getResponses().getFirst().responseData().contains("PAID:7"));

        var stored = f.chatMemory().get("tenant-a", "sess-1", 20);
        assertThat(stored).hasSize(4);
        assertThat(stored.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((AssistantMessage) stored.get(1)).hasToolCalls()).isTrue();
        assertThat(stored.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(stored.get(3).getText()).isEqualTo("Status is PAID");
    }

    public record ToolInput(int id) {
    }

    private static final class FakeChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> callFn;
        private final Function<Prompt, Flux<ChatResponse>> streamFn;

        private FakeChatModel(Function<Prompt, ChatResponse> callFn, Function<Prompt, Flux<ChatResponse>> streamFn) {
            this.callFn = callFn;
            this.streamFn = streamFn;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return callFn.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return streamFn.apply(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
