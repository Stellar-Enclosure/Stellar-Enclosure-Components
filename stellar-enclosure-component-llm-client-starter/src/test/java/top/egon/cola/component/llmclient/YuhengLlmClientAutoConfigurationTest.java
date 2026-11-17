package top.egon.cola.component.llmclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.llmclient.autoconfigure.YuhengLlmClientAutoConfiguration;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YuhengLlmClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YuhengLlmClientAutoConfiguration.class));

    @Test
    @DisplayName("默认关闭时不注册 ChatModel 与 EmbeddingModel；启用后通过网关完成调用并附带 Bearer Token 与 Cache-Control")
    void registersBeansWhenEnabledAndCallsGatewayWithToken() throws Exception {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
        });

        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> cacheHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cacheHeader.set(exchange.getRequestHeaders().getFirst("Cache-Control"));
            byte[] body = """
                    {
                      "id": "chatcmpl-1",
                      "object": "chat.completion",
                      "model": "company-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "Hello from Yuheng"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = """
                    {
                      "object": "list",
                      "model": "local-bge-m3",
                      "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            runner.withPropertyValues(
                    "egon.cola.component.llm-client.enabled=true",
                    "egon.cola.component.llm-client.base-url=" + baseUrl,
                    "egon.cola.component.llm-client.default-model=company-chat",
                    "egon.cola.component.llm-client.default-embedding-model=local-bge-m3",
                    "egon.cola.component.llm-client.cache-control=no-cache"
            ).withBean("yuhengServiceTokenSupplier",
                    top.egon.cola.component.llmclient.auth.YuhengServiceTokenInterceptor.TokenSupplier.class,
                    () -> () -> "test-service-token"
            ).run(context -> {
                assertThat(context).hasSingleBean(ChatModel.class);
                assertThat(context).hasSingleBean(EmbeddingModel.class);

                ChatModel chat = context.getBean(ChatModel.class);
                String reply = chat.call(new Prompt("hi")).getResult().getOutput().getText();
                assertThat(reply).isEqualTo("Hello from Yuheng");
                assertThat(authHeader.get()).isEqualTo("Bearer test-service-token");
                assertThat(cacheHeader.get()).isEqualTo("no-cache");

                EmbeddingModel embed = context.getBean(EmbeddingModel.class);
                float[] vector = embed.embed(List.of("hello")).get(0);
                assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
            });
        } finally {
            server.stop(0);
        }
    }
}
