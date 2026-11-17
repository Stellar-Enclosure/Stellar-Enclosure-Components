package top.egon.cola.component.llmclient.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import top.egon.cola.component.llmclient.properties.YuhengLlmClientProperties;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 中文说明：{@code YuhengSpringAiChatModel} 将 Spring AI {@link ChatModel} 统一适配至玉衡大模型网关 {@code /v1/chat/completions}。
 * English summary: {@code YuhengSpringAiChatModel} adapts Spring AI {@link ChatModel} to the Yuheng LLM Gateway {@code /v1/chat/completions}.
 */
@Slf4j
public class YuhengSpringAiChatModel implements ChatModel {

    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final YuhengLlmClientProperties properties;

    public YuhengSpringAiChatModel(
            RestClient restClient,
            ObjectMapper objectMapper,
            YuhengLlmClientProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ObjectNode body = buildRequestBody(prompt, false);
        String raw = restClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        return parseUnaryResponse(raw);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ObjectNode body = buildRequestBody(prompt, true);
        return Flux.create(sink -> {
            try {
                restClient.post()
                        .uri(COMPLETIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .body(body.toString())
                        .exchange((req, res) -> {
                            if (res.getStatusCode().isError()) {
                                sink.error(new IllegalStateException(
                                        "Yuheng LLM stream failed status=" + res.getStatusCode().value()));
                                return null;
                            }
                            try (InputStream is = res.getBody();
                                 BufferedReader reader = new BufferedReader(
                                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (!line.startsWith("data:")) {
                                        continue;
                                    }
                                    String data = line.substring(5).trim();
                                    if (data.isEmpty() || "[DONE]".equals(data)) {
                                        continue;
                                    }
                                    ChatResponse chunk = parseStreamChunk(data);
                                    if (chunk != null) {
                                        sink.next(chunk);
                                    }
                                }
                                sink.complete();
                            }
                            return null;
                        });
            } catch (Exception ex) {
                sink.error(ex);
            }
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .model(properties.getDefaultModel())
                .build();
    }

    private ObjectNode buildRequestBody(Prompt prompt, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        String model = prompt.getOptions() != null && StringUtils.isNotBlank(prompt.getOptions().getModel())
                ? prompt.getOptions().getModel()
                : properties.getDefaultModel();
        root.put("model", model);
        root.put("stream", stream);
        if (stream) {
            root.putObject("stream_options").put("include_usage", true);
        }
        if (prompt.getOptions() != null) {
            if (prompt.getOptions().getTemperature() != null) {
                root.put("temperature", prompt.getOptions().getTemperature());
            }
            if (prompt.getOptions().getMaxTokens() != null) {
                root.put("max_tokens", prompt.getOptions().getMaxTokens());
            }
        }
        ArrayNode messages = root.putArray("messages");
        for (Message msg : prompt.getInstructions()) {
            ObjectNode node = messages.addObject();
            node.put("role", mapRole(msg.getMessageType()));
            node.put("content", msg.getText() == null ? "" : msg.getText());
        }
        return root;
    }

    private ChatResponse parseUnaryResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<Generation> generations = new ArrayList<>();
            for (JsonNode choice : root.path("choices")) {
                String text = choice.path("message").path("content").asText("");
                generations.add(new Generation(new AssistantMessage(text)));
            }
            ChatResponseMetadata metadata = buildMetadata(root);
            return new ChatResponse(generations, metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Yuheng LLM unary response", ex);
        }
    }

    private ChatResponse parseStreamChunk(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            List<Generation> generations = new ArrayList<>();
            for (JsonNode choice : root.path("choices")) {
                JsonNode delta = choice.path("delta");
                if (delta.has("content") && !delta.get("content").isNull()) {
                    generations.add(new Generation(new AssistantMessage(delta.get("content").asText())));
                }
            }
            ChatResponseMetadata metadata = buildMetadata(root);
            return new ChatResponse(generations, metadata);
        } catch (Exception ex) {
            log.warn("Failed to parse Yuheng LLM SSE chunk errorType={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private ChatResponseMetadata buildMetadata(JsonNode root) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
                .id(root.path("id").asText(""))
                .model(root.path("model").asText(""));
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()) {
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);
            builder.usage(new DefaultUsage(promptTokens, completionTokens, totalTokens));
        }
        return builder.build();
    }

    private static String mapRole(MessageType type) {
        if (type == null) {
            return "user";
        }
        return switch (type) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
            default -> "user";
        };
    }
}
