package top.egon.cola.component.agentflow.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Level 1 session repository storing complete turns with tenant isolation.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class InMemorySessionChatMemoryRepository implements TurnAlignedChatMemory.TurnAwareChatMemoryRepository {

    public static final String DEFAULT_TENANT = "default";
    private static final int MAX_TURN_MESSAGES = 64;

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    private final ConcurrentHashMap<SessionKey, ConversationBucket> store = new ConcurrentHashMap<>();

    @Override
    public List<String> findConversationIds() {
        return store.keySet().stream()
                .filter(key -> DEFAULT_TENANT.equals(key.tenantKey()))
                .map(SessionKey::conversationId)
                .distinct()
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return findRecentTurns(DEFAULT_TENANT, conversationId, Integer.MAX_VALUE);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return;
        }
        appendTurn(DEFAULT_TENANT, conversationId, UUID.randomUUID().toString(), messages, clock.instant());
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        deleteByConversationId(DEFAULT_TENANT, conversationId);
    }

    @Override
    public void deleteByConversationId(String tenantKey, String conversationId) {
        ContextScopeVO.requireIdentifier(tenantKey);
        requireConversationId(conversationId);
        store.remove(new SessionKey(tenantKey, conversationId));
    }

    @Override
    public List<TurnAlignedChatMemory.SessionTurnVO> findRecentTurnEntries(String tenantKey,
                                                                          String conversationId,
                                                                          int windowTurns) {
        ContextScopeVO.requireIdentifier(tenantKey);
        requireConversationId(conversationId);
        ConversationBucket bucket = store.get(new SessionKey(tenantKey, conversationId));
        if (bucket == null) {
            return List.of();
        }
        return bucket.recentTurns(windowTurns);
    }

    @Override
    public void appendTurn(String tenantKey,
                           String conversationId,
                           String turnId,
                           List<Message> messages,
                           Instant createdAt) {
        ContextScopeVO.requireIdentifier(tenantKey);
        requireConversationId(conversationId);
        requireTurnId(turnId);
        Objects.requireNonNull(createdAt, "createdAt");
        if (messages == null || messages.isEmpty() || messages.size() > MAX_TURN_MESSAGES) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "INVALID_SESSION_TURN", "INVALID_SESSION_TURN");
        }
        List<Message> copied = copyMessages(messages);
        ConversationBucket bucket = store.computeIfAbsent(
                new SessionKey(tenantKey, conversationId), k -> new ConversationBucket());
        bucket.appendIfAbsent(turnId, copied, createdAt);
    }

    private static List<Message> copyMessages(List<Message> messages) {
        List<Message> copies = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            Objects.requireNonNull(msg, "message");
            copies.add(copySingleMessage(msg));
        }
        return List.copyOf(copies);
    }

    private static Message copySingleMessage(Message message) {
        if (message instanceof UserMessage user) {
            return new UserMessage(user.getText() == null ? "" : user.getText());
        }
        if (message instanceof SystemMessage sys) {
            return new SystemMessage(sys.getText() == null ? "" : sys.getText());
        }
        if (message instanceof AssistantMessage assistant) {
            return AssistantMessage.builder()
                    .content(assistant.getText() == null ? "" : assistant.getText())
                    .toolCalls(assistant.getToolCalls() == null ? List.of() : List.copyOf(assistant.getToolCalls()))
                    .properties(assistant.getMetadata() == null ? Map.of() : Map.copyOf(assistant.getMetadata()))
                    .build();
        }
        if (message instanceof ToolResponseMessage tool) {
            return ToolResponseMessage.builder()
                    .responses(tool.getResponses() == null ? List.of() : List.copyOf(tool.getResponses()))
                    .metadata(tool.getMetadata() == null ? Map.of() : Map.copyOf(tool.getMetadata()))
                    .build();
        }
        throw new IllegalArgumentException("Unsupported Spring AI message type: " + message.getMessageType());
    }

    static void requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 256
                || conversationId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Agent Flow conversationId");
        }
    }

    static void requireTurnId(String turnId) {
        if (turnId == null || turnId.isBlank() || turnId.length() > 64
                || turnId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Agent Flow turnId");
        }
    }

    private record SessionKey(String tenantKey, String conversationId) {
    }

    private static final class ConversationBucket {
        private final Map<String, TurnAlignedChatMemory.SessionTurnVO> turnsById = new LinkedHashMap<>();

        synchronized void appendIfAbsent(String turnId, List<Message> messages, Instant createdAt) {
            if (turnsById.containsKey(turnId)) {
                return;
            }
            turnsById.put(turnId, new TurnAlignedChatMemory.SessionTurnVO(turnId, messages, createdAt));
        }

        synchronized List<TurnAlignedChatMemory.SessionTurnVO> recentTurns(int windowTurns) {
            if (turnsById.isEmpty() || windowTurns <= 0) {
                return List.of();
            }
            List<TurnAlignedChatMemory.SessionTurnVO> all = new ArrayList<>(turnsById.values());
            int fromIndex = Math.max(0, all.size() - windowTurns);
            return List.copyOf(all.subList(fromIndex, all.size()));
        }
    }
}
