package top.egon.cola.component.llmclient.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import top.egon.cola.component.llmclient.properties.YuhengLlmClientProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文说明：{@code YuhengSpringAiEmbeddingModel} 将 Spring AI {@link EmbeddingModel} 统一适配至玉衡大模型网关 {@code /v1/embeddings}。
 * English summary: {@code YuhengSpringAiEmbeddingModel} adapts Spring AI {@link EmbeddingModel} to Yuheng LLM Gateway {@code /v1/embeddings}.
 */
@Slf4j
public class YuhengSpringAiEmbeddingModel implements EmbeddingModel {

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final YuhengLlmClientProperties properties;

    public YuhengSpringAiEmbeddingModel(
            RestClient restClient,
            ObjectMapper objectMapper,
            YuhengLlmClientProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        String model = request.getOptions() != null && StringUtils.isNotBlank(request.getOptions().getModel())
                ? request.getOptions().getModel()
                : properties.getDefaultEmbeddingModel();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String text : request.getInstructions()) {
            input.add(text);
        }
        String raw = restClient.post()
                .uri(EMBEDDINGS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<Embedding> results = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                JsonNode vecNode = item.path("embedding");
                float[] vec = new float[vecNode.size()];
                for (int i = 0; i < vecNode.size(); i++) {
                    vec[i] = (float) vecNode.get(i).asDouble();
                }
                results.add(new Embedding(vec, item.path("index").asInt(results.size())));
            }
            return new EmbeddingResponse(results);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Yuheng LLM embedding response", ex);
        }
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }
}
