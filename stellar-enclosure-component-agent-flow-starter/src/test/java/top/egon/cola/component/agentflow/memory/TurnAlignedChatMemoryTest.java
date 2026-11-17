package top.egon.cola.component.agentflow.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnAlignedChatMemoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void keepsRecentTwentyTurnsByDefault() {
        var repository = new InMemorySessionChatMemoryRepository(FIXED_CLOCK);
        var memory = new TurnAlignedChatMemory(repository, 20, FIXED_CLOCK);

        for (int turn = 1; turn <= 25; turn++) {
            memory.appendTurn("tenant-a", "sess-1", "turn-" + turn, List.of(
                    new UserMessage("user-" + turn),
                    new AssistantMessage("assistant-" + turn)));
        }

        List<Message> retained = memory.get("tenant-a", "sess-1", null);
        assertThat(retained).hasSize(40);
        assertThat(retained.getFirst().getText()).isEqualTo("user-6");
        assertThat(retained.getLast().getText()).isEqualTo("assistant-25");

        // Spring AI ChatMemory#get(conversationId) uses default tenant ("default") and default window (20).
        for (int turn = 1; turn <= 22; turn++) {
            memory.appendTurn("default", "sess-default", "d-" + turn, List.of(
                    new UserMessage("du-" + turn),
                    new AssistantMessage("da-" + turn)));
        }
        List<Message> viaChatMemory = memory.get("sess-default");
        assertThat(viaChatMemory).hasSize(40);
        assertThat(viaChatMemory.getFirst().getText()).isEqualTo("du-3");
        assertThat(viaChatMemory.getLast().getText()).isEqualTo("da-22");
    }

    @Test
    void supportsCustomWindowTurnsAndNeverSplitsToolTurn() {
        var repository = new InMemorySessionChatMemoryRepository(FIXED_CLOCK);
        var memory = new TurnAlignedChatMemory(repository, 20, FIXED_CLOCK);

        for (int turn = 1; turn <= 25; turn++) {
            if (turn == 6 || turn == 21) {
                var toolCall = new AssistantMessage.ToolCall("tc-" + turn, "function", "querySpec", "{\"t\":" + turn + "}");
                var callMsg = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
                var toolResp = ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("tc-" + turn, "querySpec", "{\"ok\":true}")))
                        .build();
                memory.appendTurn("tenant-a", "sess-tool", "turn-" + turn, List.of(
                        new UserMessage("user-" + turn),
                        callMsg,
                        toolResp,
                        new AssistantMessage("final-" + turn)));
            } else {
                memory.appendTurn("tenant-a", "sess-tool", "turn-" + turn, List.of(
                        new UserMessage("user-" + turn),
                        new AssistantMessage("final-" + turn)));
            }
        }

        List<Message> window20 = memory.get("tenant-a", "sess-tool", 20);
        assertThat(window20.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(window20.getFirst().getText()).isEqualTo("user-6");
        assertThat(window20.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) window20.get(1)).hasToolCalls()).isTrue();
        assertThat(window20.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(window20.get(3).getText()).isEqualTo("final-6");

        List<Message> window5 = memory.get("tenant-a", "sess-tool", 5);
        assertThat(window5.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(window5.getFirst().getText()).isEqualTo("user-21");
        assertThat(window5.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(window5.getLast().getText()).isEqualTo("final-25");

        // Head-alignment safety guard drops any orphaned leading ToolResponseMessage.
        var orphanTool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("orphan", "fn", "{}")))
                .build();
        List<Message> aligned = TurnAlignedChatMemory.alignHead(List.of(
                orphanTool, new UserMessage("clean-user"), new AssistantMessage("clean-assistant")));
        assertThat(aligned).hasSize(2);
        assertThat(aligned.getFirst()).isInstanceOf(UserMessage.class);
    }

    @Test
    void restoresToolCallsAndToolResponsesWithTenantIsolation() {
        var repository = new InMemorySessionChatMemoryRepository(FIXED_CLOCK);
        var memory = new TurnAlignedChatMemory(repository, 20, FIXED_CLOCK);

        var toolCall = new AssistantMessage.ToolCall("call-42", "function", "fetchDoc", "{\"id\":\"doc-1\"}");
        var assistantToolCall = AssistantMessage.builder().content("calling tool").toolCalls(List.of(toolCall)).build();
        var toolResp = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-42", "fetchDoc", "{\"md\":\"# Hi\"}")))
                .metadata(Map.of())
                .build();

        memory.appendTurn("tenant-a", "shared-conv", "t1", List.of(
                new UserMessage("run"), assistantToolCall, toolResp, new AssistantMessage("done")));
        // Idempotent replay of same turnId must be ignored cleanly.
        memory.appendTurn("tenant-a", "shared-conv", "t1", List.of(
                new UserMessage("duplicate"), new AssistantMessage("duplicate")));

        memory.appendTurn("tenant-b", "shared-conv", "t1", List.of(
                new UserMessage("tenant-b-only"), new AssistantMessage("resp-b")));

        List<Message> tenantA = memory.get("tenant-a", "shared-conv", 20);
        assertThat(tenantA).hasSize(4);
        assertThat(tenantA.getFirst().getText()).isEqualTo("run");
        assertThat(((AssistantMessage) tenantA.get(1)).getToolCalls()).containsExactly(toolCall);
        assertThat(((ToolResponseMessage) tenantA.get(2)).getResponses().getFirst().responseData())
                .isEqualTo("{\"md\":\"# Hi\"}");

        List<Message> tenantB = memory.get("tenant-b", "shared-conv", 20);
        assertThat(tenantB).hasSize(2);
        assertThat(tenantB.getFirst().getText()).isEqualTo("tenant-b-only");

        memory.clear("tenant-a", "shared-conv");
        assertThat(memory.get("tenant-a", "shared-conv", 20)).isEmpty();
        assertThat(memory.get("tenant-b", "shared-conv", 20)).hasSize(2);
    }

    @Test
    void rejectsInvalidWindowTurns() {
        var repository = new InMemorySessionChatMemoryRepository(FIXED_CLOCK);
        var memory = new TurnAlignedChatMemory(repository, 20, FIXED_CLOCK);

        assertThatThrownBy(() -> memory.get("tenant-a", "sess-1", 0))
                .isInstanceOf(CommonException.class)
                .hasMessage("INVALID_SESSION_WINDOW");
        assertThatThrownBy(() -> memory.get("tenant-a", "sess-1", 201))
                .isInstanceOf(CommonException.class)
                .hasMessage("INVALID_SESSION_WINDOW");
    }
}
