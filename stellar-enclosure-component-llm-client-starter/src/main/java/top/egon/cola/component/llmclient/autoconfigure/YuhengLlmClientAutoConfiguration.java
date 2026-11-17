package top.egon.cola.component.llmclient.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import top.egon.cola.component.llmclient.adapter.YuhengSpringAiChatModel;
import top.egon.cola.component.llmclient.adapter.YuhengSpringAiEmbeddingModel;
import top.egon.cola.component.llmclient.auth.YuhengServiceTokenInterceptor;
import top.egon.cola.component.llmclient.properties.YuhengLlmClientProperties;

/**
 * 中文说明：{@code YuhengLlmClientAutoConfiguration} 自动装配统一连接玉衡大模型网关的 Spring AI 客户端 Bean。
 * English summary: {@code YuhengLlmClientAutoConfiguration} auto-configures Spring AI client beans backed by Yuheng LLM Gateway.
 */
@AutoConfiguration
@EnableConfigurationProperties(YuhengLlmClientProperties.class)
@ConditionalOnProperty(prefix = "egon.cola.component.llm-client", name = "enabled", havingValue = "true")
public class YuhengLlmClientAutoConfiguration {

    @Bean("yuhengLlmRestClient")
    @ConditionalOnMissingBean(name = "yuhengLlmRestClient")
    public RestClient yuhengLlmRestClient(
            YuhengLlmClientProperties properties,
            ObjectProvider<YuhengServiceTokenInterceptor.TokenSupplier> tokenSupplierProvider) {
        YuhengServiceTokenInterceptor.TokenSupplier supplier = tokenSupplierProvider.getIfAvailable(() ->
                () -> StringUtils.trimToNull(properties.getApiKey()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestInterceptor(new YuhengServiceTokenInterceptor(supplier, properties.getCacheControl()))
                .build();
    }

    @Bean("yuhengSpringAiChatModel")
    @ConditionalOnMissingBean(name = "yuhengSpringAiChatModel")
    public YuhengSpringAiChatModel yuhengSpringAiChatModel(
            @Qualifier("yuhengLlmRestClient") RestClient yuhengLlmRestClient,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            YuhengLlmClientProperties properties) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new YuhengSpringAiChatModel(yuhengLlmRestClient, mapper, properties);
    }

    @Bean("yuhengSpringAiEmbeddingModel")
    @ConditionalOnMissingBean(name = "yuhengSpringAiEmbeddingModel")
    public YuhengSpringAiEmbeddingModel yuhengSpringAiEmbeddingModel(
            @Qualifier("yuhengLlmRestClient") RestClient yuhengLlmRestClient,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            YuhengLlmClientProperties properties) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new YuhengSpringAiEmbeddingModel(yuhengLlmRestClient, mapper, properties);
    }
}
