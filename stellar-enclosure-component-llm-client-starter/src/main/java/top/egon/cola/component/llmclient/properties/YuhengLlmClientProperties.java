package top.egon.cola.component.llmclient.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 中文说明：{@code YuhengLlmClientProperties} 绑定统一玉衡 LLM 网关客户端配置。
 * English summary: {@code YuhengLlmClientProperties} binds the unified Yuheng LLM gateway client configuration.
 */
@Validated
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@ConfigurationProperties(prefix = "egon.cola.component.llm-client")
public class YuhengLlmClientProperties {

    private boolean enabled;

    @NotBlank
    private String baseUrl = "https://llm.yuheng.internal";

    private String defaultModel;

    private String defaultEmbeddingModel;

    private String cacheControl;

    private String apiKey;
}
