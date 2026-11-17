package top.egon.cola.component.llmclient.auth;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 中文说明：{@code YuhengServiceTokenInterceptor} 自动在请求玉衡大模型网关时附加 Bearer Token 与可选 Cache-Control 头。
 * English summary: {@code YuhengServiceTokenInterceptor} automatically attaches Bearer token and optional Cache-Control header on Yuheng LLM gateway calls.
 */
@Slf4j
public class YuhengServiceTokenInterceptor implements ClientHttpRequestInterceptor {

    @FunctionalInterface
    public interface TokenSupplier {
        String getToken();

        default void invalidate() {
        }
    }

    private final TokenSupplier tokenSupplier;
    private final String cacheControl;

    public YuhengServiceTokenInterceptor(TokenSupplier tokenSupplier, String cacheControl) {
        this.tokenSupplier = tokenSupplier;
        this.cacheControl = cacheControl;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        if (tokenSupplier != null) {
            String token = tokenSupplier.getToken();
            if (StringUtils.isNotBlank(token)) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
        }
        if (StringUtils.isNotBlank(cacheControl)
                && !request.getHeaders().containsHeader(HttpHeaders.CACHE_CONTROL)) {
            request.getHeaders().set(HttpHeaders.CACHE_CONTROL, cacheControl);
        }
        ClientHttpResponse response = execution.execute(request, body);
        int status = response.getStatusCode().value();
        if ((status == 401 || status == 403) && tokenSupplier != null) {
            tokenSupplier.invalidate();
        }
        return response;
    }
}
