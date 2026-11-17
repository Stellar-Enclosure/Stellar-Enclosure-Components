package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.memory.InMemorySessionChatMemoryRepository;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Option 1B PgSQL repository persisting Spring AI turns (including toolCalls and toolResponses JSONB)
 * into {@code <schema>.session_message} with transactional advisory lock sequencing.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class JdbcSessionChatMemoryRepository implements TurnAlignedChatMemory.TurnAwareChatMemoryRepository {

    private static final int MAX_TURN_MESSAGES = 64;
    private static final int MAX_TOOL_PAYLOAD_BYTES = 256 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final String schema;

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    private final JsonMapper jsonMapper;

    public JdbcSessionChatMemoryRepository(DataSource dataSource, String schema, Clock clock) {
        this(dataSource, schema, clock, JsonMapper.builder().build());
    }

    @Override
    public List<String> findConversationIds() {
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        JdbcTemplate jdbc = readJdbc();
        String sql = "SELECT DISTINCT conversation_id FROM " + verifiedSchema
                + ".session_message WHERE tenant_key = ? ORDER BY conversation_id ASC LIMIT 1000";
        try {
            return jdbc.queryForList(sql, String.class, InMemorySessionChatMemoryRepository.DEFAULT_TENANT);
        } catch (DataAccessException ex) {
            log.warn("Failed listing conversation ids from schema {}", verifiedSchema);
            throw unavailable(ex);
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return findRecentTurns(
                InMemorySessionChatMemoryRepository.DEFAULT_TENANT,
                conversationId,
                TurnAlignedChatMemory.DEFAULT_WINDOW_TURNS);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return;
        }
        appendTurn(InMemorySessionChatMemoryRepository.DEFAULT_TENANT, conversationId,
                UUID.randomUUID().toString(), messages, clock.instant());
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        deleteByConversationId(InMemorySessionChatMemoryRepository.DEFAULT_TENANT, conversationId);
    }

    @Override
    public void deleteByConversationId(String tenantKey, String conversationId) {
        ContextScopeVO.requireIdentifier(tenantKey);
        requireConversationId(conversationId);
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        TransactionTemplate tx = writeTransaction();
        JdbcTemplate jdbc = writeJdbc();
        try {
            tx.executeWithoutResult(status -> jdbc.update(
                    "DELETE FROM " + verifiedSchema + ".session_message WHERE tenant_key = ? AND conversation_id = ?",
                    tenantKey, conversationId));
        } catch (DataAccessException ex) {
            log.warn("Failed deleting session messages in schema {}", verifiedSchema);
            throw unavailable(ex);
        }
    }

    @Override
    public List<TurnAlignedChatMemory.SessionTurnVO> findRecentTurnEntries(String tenantKey,
                                                                          String conversationId,
                                                                          int windowTurns) {
        ContextScopeVO.requireIdentifier(tenantKey);
        requireConversationId(conversationId);
        if (windowTurns <= 0) {
            return List.of();
        }
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        JdbcTemplate jdbc = readJdbc();
        String sql = """
                WITH recent_turns AS (
                    SELECT turn_id, MAX(message_seq) AS last_seq
                    FROM %s.session_message
                    WHERE tenant_key = ? AND conversation_id = ?
                    GROUP BY turn_id
                    ORDER BY last_seq DESC
                    LIMIT ?
                )
                SELECT m.message_seq, m.turn_id, m.message_type, m.content_text,
                       m.tool_payload::text AS tool_payload_json, m.created_at
                FROM %s.session_message m
                JOIN recent_turns rt ON rt.turn_id = m.turn_id
                WHERE m.tenant_key = ? AND m.conversation_id = ?
                ORDER BY m.message_seq ASC
                """.formatted(verifiedSchema, verifiedSchema);

        try {
            List<StoredMessageRow> rows = jdbc.query(sql, (rs, rowNum) -> {
                String turnId = rs.getString("turn_id");
                String type = rs.getString("message_type");
                String contentText = rs.getString("content_text");
                String payloadJson = rs.getString("tool_payload_json");
                OffsetDateTime createdOffset = rs.getObject("created_at", OffsetDateTime.class);
                Instant created = createdOffset == null ? clock.instant() : createdOffset.toInstant();
                return new StoredMessageRow(turnId, deserializeMessage(type, contentText, payloadJson), created);
            }, tenantKey, conversationId, windowTurns, tenantKey, conversationId);

            Map<String, List<Message>> byTurn = new LinkedHashMap<>();
            Map<String, Instant> turnTimes = new LinkedHashMap<>();
            for (StoredMessageRow row : rows) {
                byTurn.computeIfAbsent(row.turnId(), k -> new ArrayList<>()).add(row.message());
                turnTimes.putIfAbsent(row.turnId(), row.createdAt());
            }
            List<TurnAlignedChatMemory.SessionTurnVO> turns = new ArrayList<>(byTurn.size());
            byTurn.forEach((id, msgs) -> turns.add(new TurnAlignedChatMemory.SessionTurnVO(id, msgs, turnTimes.get(id))));
            return List.copyOf(turns);
        } catch (DataAccessException ex) {
            log.warn("Failed loading recent turns from schema {}", verifiedSchema);
            throw unavailable(ex);
        }
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
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        List<SerializedMessageRow> encoded = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            encoded.add(serializeMessage(msg));
        }
        OffsetDateTime utcTimestamp = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);

        TransactionTemplate tx = writeTransaction();
        JdbcTemplate jdbc = writeJdbc();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))::text",
                        String.class,
                        tenantKey,
                        conversationId);

                List<Integer> existing = jdbc.queryForList(
                        "SELECT 1 FROM " + verifiedSchema
                                + ".session_message WHERE tenant_key = ? AND conversation_id = ? AND turn_id = ? LIMIT 1",
                        Integer.class,
                        tenantKey,
                        conversationId,
                        turnId);
                if (!existing.isEmpty()) {
                    return;
                }

                Long currentMax = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(message_seq), 0) FROM " + verifiedSchema
                                + ".session_message WHERE tenant_key = ? AND conversation_id = ?",
                        Long.class,
                        tenantKey,
                        conversationId);
                long baseSeq = currentMax == null ? 0L : currentMax;

                String insertSql = "INSERT INTO " + verifiedSchema
                        + ".session_message (tenant_key, conversation_id, message_seq, turn_id, message_type, "
                        + "content_text, tool_payload, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)";
                jdbc.batchUpdate(insertSql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int rowIndex) throws java.sql.SQLException {
                        SerializedMessageRow row = encoded.get(rowIndex);
                        ps.setString(1, tenantKey);
                        ps.setString(2, conversationId);
                        ps.setLong(3, baseSeq + rowIndex + 1L);
                        ps.setString(4, turnId);
                        ps.setString(5, row.messageType());
                        if (row.contentText() == null) {
                            ps.setNull(6, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(6, row.contentText());
                        }
                        if (row.toolPayloadJson() == null) {
                            ps.setNull(7, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(7, row.toolPayloadJson());
                        }
                        ps.setObject(8, utcTimestamp);
                    }

                    @Override
                    public int getBatchSize() {
                        return encoded.size();
                    }
                });
            });
        } catch (DataAccessException ex) {
            log.warn("Failed appending turn into schema {}", verifiedSchema);
            throw unavailable(ex);
        }
    }

    private SerializedMessageRow serializeMessage(Message message) {
        Objects.requireNonNull(message, "message");
        MessageType type = message.getMessageType();
        try {
            if (message instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                String payloadJson = null;
                if (assistant.hasToolCalls()) {
                    List<ToolCallWire> calls = assistant.getToolCalls().stream()
                            .map(c -> new ToolCallWire(c.id(), c.type(), c.name(), c.arguments()))
                            .toList();
                    payloadJson = writeBoundedJson(Map.of("toolCalls", calls));
                } else if (text == null) {
                    text = "";
                }
                return new SerializedMessageRow(MessageType.ASSISTANT.name(), text, payloadJson);
            }
            if (message instanceof ToolResponseMessage tool) {
                List<ToolResponseWire> responses = tool.getResponses().stream()
                        .map(r -> new ToolResponseWire(r.id(), r.name(), r.responseData()))
                        .toList();
                String payloadJson = writeBoundedJson(Map.of("responses", responses));
                return new SerializedMessageRow(MessageType.TOOL.name(), tool.getText(), payloadJson);
            }
            if (message instanceof SystemMessage system) {
                return new SerializedMessageRow(MessageType.SYSTEM.name(),
                        system.getText() == null ? "" : system.getText(), null);
            }
            if (message instanceof UserMessage user) {
                return new SerializedMessageRow(MessageType.USER.name(),
                        user.getText() == null ? "" : user.getText(), null);
            }
            throw new IllegalArgumentException("Unsupported Spring AI message type: " + type);
        } catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    private Message deserializeMessage(String type, String contentText, String payloadJson) {
        try {
            MessageType messageType = MessageType.valueOf(type);
            return switch (messageType) {
                case USER -> new UserMessage(contentText == null ? "" : contentText);
                case SYSTEM -> new SystemMessage(contentText == null ? "" : contentText);
                case ASSISTANT -> {
                    List<AssistantMessage.ToolCall> toolCalls = List.of();
                    if (payloadJson != null && !payloadJson.isBlank()) {
                        JsonNode root = jsonMapper.readTree(payloadJson);
                        JsonNode callsNode = root.path("toolCalls");
                        if (callsNode.isArray()) {
                            List<AssistantMessage.ToolCall> parsed = new ArrayList<>();
                            for (JsonNode node : callsNode) {
                                parsed.add(new AssistantMessage.ToolCall(
                                        node.path("id").asText(),
                                        node.path("type").asText(),
                                        node.path("name").asText(),
                                        node.path("arguments").asText()));
                            }
                            toolCalls = List.copyOf(parsed);
                        }
                    }
                    yield AssistantMessage.builder()
                            .content(contentText == null ? "" : contentText)
                            .toolCalls(toolCalls)
                            .build();
                }
                case TOOL -> {
                    List<ToolResponseMessage.ToolResponse> responses = List.of();
                    if (payloadJson != null && !payloadJson.isBlank()) {
                        JsonNode root = jsonMapper.readTree(payloadJson);
                        JsonNode respNode = root.path("responses");
                        if (respNode.isArray()) {
                            List<ToolResponseMessage.ToolResponse> parsed = new ArrayList<>();
                            for (JsonNode node : respNode) {
                                parsed.add(new ToolResponseMessage.ToolResponse(
                                        node.path("id").asText(),
                                        node.path("name").asText(),
                                        node.path("responseData").asText()));
                            }
                            responses = List.copyOf(parsed);
                        }
                    }
                    yield ToolResponseMessage.builder().responses(responses).build();
                }
            };
        } catch (IOException | RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    private String writeBoundedJson(Object payload) throws IOException {
        String json = jsonMapper.writeValueAsString(payload);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_TOOL_PAYLOAD_BYTES) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "SESSION_TOOL_PAYLOAD_TOO_LARGE", "SESSION_TOOL_PAYLOAD_TOO_LARGE");
        }
        return json;
    }

    private JdbcTemplate readJdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return jdbc;
    }

    private JdbcTemplate writeJdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return jdbc;
    }

    private TransactionTemplate writeTransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return tx;
    }

    private static void requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 256
                || conversationId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Agent Flow conversationId");
        }
    }

    private static void requireTurnId(String turnId) {
        if (turnId == null || turnId.isBlank() || turnId.length() > 64
                || turnId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Agent Flow turnId");
        }
    }

    private static CommonException unavailable(Exception cause) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                "SESSION_MEMORY_UNAVAILABLE", "SESSION_MEMORY_UNAVAILABLE");
    }

    private record StoredMessageRow(String turnId, Message message, Instant createdAt) {
    }

    private record SerializedMessageRow(String messageType, String contentText, String toolPayloadJson) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolCallWire(String id, String type, String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolResponseWire(String id, String name, String responseData) {
    }
}
