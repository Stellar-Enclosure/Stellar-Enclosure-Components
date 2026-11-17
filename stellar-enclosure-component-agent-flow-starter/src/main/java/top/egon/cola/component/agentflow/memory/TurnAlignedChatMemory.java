package top.egon.cola.component.agentflow.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring AI {@link ChatMemory} decorator enforcing full-turn sliding windows (default 20, range 1..200)
 * and head alignment so tool call/response sequences are never split.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TurnAlignedChatMemory implements ChatMemory {

    public static final int DEFAULT_WINDOW_TURNS = AgentFlowProperties.MemorySettings.DEFAULT_SESSION_WINDOW_TURNS;
    public static final int MAX_WINDOW_TURNS = AgentFlowProperties.MemorySettings.MAX_SESSION_WINDOW_TURNS;

    @Qualifier("threeTierSessionRepository")
    private final TurnAwareChatMemoryRepository repository;

    private final int defaultWindowTurns;

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    /** Extended repository SPI preserving turn grouping and tenant isolation. */
    public interface TurnAwareChatMemoryRepository extends ChatMemoryRepository {

        List<SessionTurnVO> findRecentTurnEntries(String tenantKey, String conversationId, int windowTurns);

        default List<Message> findRecentTurns(String tenantKey, String conversationId, int windowTurns) {
            return findRecentTurnEntries(tenantKey, conversationId, windowTurns).stream()
                    .flatMap(turn -> turn.messages().stream())
                    .toList();
        }

        void appendTurn(String tenantKey, String conversationId, String turnId, List<Message> messages, Instant createdAt);

        void deleteByConversationId(String tenantKey, String conversationId);
    }

    /** Immutable grouped turn entry representing one complete user-agent-tool interaction round. */
    public record SessionTurnVO(String turnId, List<Message> messages, Instant createdAt) {
        public SessionTurnVO {
            InMemorySessionChatMemoryRepository.requireTurnId(turnId);
            Objects.requireNonNull(messages, "messages");
            messages = List.copyOf(messages);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        appendTurn(InMemorySessionChatMemoryRepository.DEFAULT_TENANT, conversationId,
                UUID.randomUUID().toString(), messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return get(InMemorySessionChatMemoryRepository.DEFAULT_TENANT, conversationId, defaultWindowTurns);
    }

    @Override
    public void clear(String conversationId) {
        clear(InMemorySessionChatMemoryRepository.DEFAULT_TENANT, conversationId);
    }

    public List<Message> get(String tenantKey, String conversationId, Integer windowTurns) {
        List<SessionTurnVO> turns = getTurns(tenantKey, conversationId, windowTurns);
        List<Message> flattened = new ArrayList<>();
        for (SessionTurnVO turn : turns) {
            flattened.addAll(turn.messages());
        }
        return alignHead(flattened);
    }

    public List<SessionTurnVO> getTurns(String tenantKey, String conversationId, Integer windowTurns) {
        int effectiveWindow = resolveWindowTurns(windowTurns);
        List<SessionTurnVO> rawTurns = repository.findRecentTurnEntries(tenantKey, conversationId, effectiveWindow);
        return alignTurnHeads(rawTurns);
    }

    public void appendTurn(String tenantKey, String conversationId, String turnId, List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return;
        }
        repository.appendTurn(tenantKey, conversationId, turnId, messages, clock.instant());
    }

    public void clear(String tenantKey, String conversationId) {
        repository.deleteByConversationId(tenantKey, conversationId);
    }

    public int resolveWindowTurns(Integer overrideTurns) {
        int candidate = overrideTurns == null ? defaultWindowTurns : overrideTurns;
        if (candidate < 1 || candidate > MAX_WINDOW_TURNS) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "INVALID_SESSION_WINDOW", "INVALID_SESSION_WINDOW");
        }
        return candidate;
    }

    /** Drops any orphaned leading ToolResponseMessage so ChatModel never receives dangling tool results. */
    public static List<Message> alignHead(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start) instanceof ToolResponseMessage) {
            start++;
        }
        return start == 0 ? List.copyOf(messages) : List.copyOf(messages.subList(start, messages.size()));
    }

    private static List<SessionTurnVO> alignTurnHeads(List<SessionTurnVO> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < turns.size() && (turns.get(start).messages().isEmpty()
                || turns.get(start).messages().getFirst() instanceof ToolResponseMessage)) {
            start++;
        }
        return start == 0 ? List.copyOf(turns) : List.copyOf(turns.subList(start, turns.size()));
    }
}
