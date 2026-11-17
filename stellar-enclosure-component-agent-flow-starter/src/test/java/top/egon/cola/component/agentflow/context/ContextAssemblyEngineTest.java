package top.egon.cola.component.agentflow.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextTrimReasonEnum;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAssemblyEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
    private static final ContextScopeVO SCOPE = new ContextScopeVO(
            "crm", "tenant-a", "user-1", "agent-1", "sess-1", "chat");

    private final ContextAssemblyEngine engine = new ContextAssemblyEngine(new JTokkitTokenCountEstimator());

    @Test
    void keepsSystemPromptIsolatedAndWrapsMemoryInReferenceBlock() {
        ContextEnvelopeBO envelope = fullEnvelope(new ContextBudgetPolicyVO(4096, 512, 128, 1, false));
        ContextAssemblyEngine.AssembledPromptVO assembled = engine.assemble(envelope, null);

        Prompt prompt = assembled.prompt();
        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(instructions.getFirst().getText()).isEqualTo("You are a strict coding assistant.");
        assertThat(instructions.getFirst().getText())
                .doesNotContain("Prefer PostgreSQL")
                .doesNotContain("Runbook Step")
                .doesNotContain("RAG chunk A");

        assertThat(instructions.getLast()).isInstanceOf(UserMessage.class);
        assertThat(instructions.getLast().getText()).isEqualTo("How should we deploy?");

        Message contextRefMsg = instructions.get(instructions.size() - 2);
        assertThat(contextRefMsg).isInstanceOf(UserMessage.class);
        assertThat(contextRefMsg.getText())
                .contains("<context_data trust=\"reference_only\">")
                .contains("<mid_term_memory>")
                .contains("Prefer PostgreSQL")
                .contains("<doc_memory id=\"d1\" title=\"Runbook\">")
                .contains("# Runbook Step")
                .contains("<others_context>")
                .contains("RAG chunk A");
    }

    @Test
    void trimsOthersThenTailDocsThenTextThenOldTurns() {
        // Construct an envelope with measurable content across every zone.
        String padding = "word ".repeat(45);
        ContextEnvelopeBO envelope = ContextEnvelopeBO.builder()
                .scope(SCOPE)
                .systemPrompt("System.")
                .rawUserMessage(new UserMessage("Question?"))
                .sessionTurns(List.of(
                        new TurnAlignedChatMemory.SessionTurnVO("t1", List.of(
                                new UserMessage("old-1 " + padding),
                                AssistantMessage.builder().content("")
                                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "f", "{}")))
                                        .build(),
                                ToolResponseMessage.builder()
                                        .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "f", "{\"r\":1}")))
                                        .build(),
                                new AssistantMessage("ans-1")), NOW),
                        new TurnAlignedChatMemory.SessionTurnVO("t2", List.of(
                                new UserMessage("recent-2"),
                                new AssistantMessage("ans-2")), NOW)))
                .textMemory(new TextMemoryVO("tm-1", 1L, "text-mem " + padding, ("text-mem " + padding).length(), NOW))
                .docMemories(List.of(
                        new DocMemoryVO("d1", "Doc1", "doc1 " + padding, null, NOW),
                        new DocMemoryVO("d2", "Doc2", "doc2 " + padding, null, NOW)))
                .otherFragments(List.of("other-1 " + padding))
                .budgetPolicy(new ContextBudgetPolicyVO(180, 100, 20, 1, true))
                .build();

        // Available budget = 180 - 100 - 20 = 60 tokens.
        // Trimming must sequentially drop Others -> d2 -> d1 -> TextMemory -> t1 (whole turn), leaving t2 intact.
        ContextAssemblyEngine.AssembledPromptVO assembled = engine.assemble(envelope, null);
        var reasons = assembled.report().trimDetails().stream()
                .map(d -> d.reason())
                .toList();

        assertThat(reasons).containsSubsequence(
                ContextTrimReasonEnum.BUDGET_OTHERS,
                ContextTrimReasonEnum.BUDGET_DOC_MEMORY,
                ContextTrimReasonEnum.BUDGET_TEXT_MEMORY,
                ContextTrimReasonEnum.BUDGET_HISTORY_TURN);
        assertThat(assembled.report().historyTurnsIncluded()).isEqualTo(1);
        assertThat(assembled.report().historyTurnsTrimmed()).isEqualTo(1);
        assertThat(assembled.report().estimatedInputTokens())
                .isLessThanOrEqualTo(assembled.report().availableInputTokens());

        // t1 was trimmed cleanly as a whole turn so no ToolResponseMessage leaks into prompt.
        assertThat(assembled.prompt().getInstructions())
                .noneMatch(m -> m instanceof ToolResponseMessage);
        assertThat(assembled.prompt().getInstructions())
                .anyMatch(m -> m instanceof UserMessage && "recent-2".equals(m.getText()));
    }

    @Test
    void failsFastWhenSystemAndUserExceedBudget() {
        ContextEnvelopeBO envelope = ContextEnvelopeBO.builder()
                .scope(SCOPE)
                .systemPrompt("System instruction ".repeat(40))
                .rawUserMessage(new UserMessage("User prompt ".repeat(30)))
                .budgetPolicy(new ContextBudgetPolicyVO(150, 50, 50, 0, true))
                .build();

        assertThatThrownBy(() -> engine.assemble(envelope, null))
                .isInstanceOf(CommonException.class)
                .hasMessage("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void supportsCombiningContextIntoFinalUserMessage() {
        ContextEnvelopeBO combined = fullEnvelope(new ContextBudgetPolicyVO(4096, 512, 128, 1, true));
        ContextAssemblyEngine.AssembledPromptVO result = engine.assemble(combined, null);

        List<Message> messages = result.prompt().getInstructions();
        // System + 2 msgs of history + 1 combined final UserMessage = 4 messages.
        assertThat(messages).hasSize(4);
        assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
        assertThat(messages.getLast().getText())
                .contains("<context_data trust=\"reference_only\">")
                .contains("How should we deploy?");
    }

    private static ContextEnvelopeBO fullEnvelope(ContextBudgetPolicyVO budgetPolicy) {
        return ContextEnvelopeBO.builder()
                .scope(SCOPE)
                .systemPrompt("You are a strict coding assistant.")
                .rawUserMessage(new UserMessage("How should we deploy?"))
                .sessionTurns(List.of(
                        new TurnAlignedChatMemory.SessionTurnVO("t1", List.of(
                                new UserMessage("Hi"),
                                new AssistantMessage("Hello")), NOW)))
                .textMemory(new TextMemoryVO("tm-1", 1L, "Prefer PostgreSQL", 17, NOW))
                .docMemories(List.of(new DocMemoryVO("d1", "Runbook", "# Runbook Step", null, NOW)))
                .otherFragments(List.of("RAG chunk A"))
                .budgetPolicy(budgetPolicy)
                .build();
    }
}
