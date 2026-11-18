package top.egon.cola.component.rpc.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = "egon.cola.component.rpc", ignoreUnknownFields = false)
public class EgonRpcProperties {

    private boolean enabled;
    @NotNull
    @Pattern(regexp = RpcTransportSecurity.H2C)
    private String transport = RpcTransportSecurity.H2C;
    @Valid
    @NotNull
    private Identity identity = new Identity();
    @Valid
    @NotNull
    private Provider provider = new Provider();
    @Valid
    @NotNull
    private Consumer consumer = new Consumer();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class Provider {

        private boolean enabled;
        @NotBlank
        private String bindAddress = "0.0.0.0";
        @Min(1)
        @Max(65535)
        private int port = 19090;
        @Min(1024)
        private int maxInboundMessageSize = 4 * 1024 * 1024;
        @Positive
        private long gracefulShutdownTimeoutMs = 10_000;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class Identity {

        private String env = "default";
        private String host;
        private String instanceId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class Consumer {

        private boolean enabled;
        @Positive
        private long defaultTimeoutMs = 3_000;
        @Positive
        private long channelDrainTimeoutMs = 5_000;
        @Positive
        @Max(4096)
        private int genericCacheMaxEntries = 256;
        @Min(1000)
        private long genericCacheIdleTimeoutMs = 600_000;
        @Min(1024)
        private int maxInboundMessageSize = 4 * 1024 * 1024;
        @Positive
        private int connectTimeoutMs = 3_000;
        @NotNull
        private Map<@NotBlank String, @NotNull @Valid RpcServiceBindingConfig> bindings = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class RpcServiceBindingConfig {

        @Positive
        private long version;
        @NotBlank
        private String namespace;
        @NotBlank
        private String service;
        @Min(1)
        @Max(65535)
        private int port;
        @NotEmpty
        private Set<@NotBlank String> methods = Set.of();
    }
}
