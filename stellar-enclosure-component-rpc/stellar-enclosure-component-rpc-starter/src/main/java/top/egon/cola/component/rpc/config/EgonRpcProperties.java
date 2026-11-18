package top.egon.cola.component.rpc.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;

import java.time.Duration;
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

    private static final long MAX_EXACT_LUA_INTEGER = 9_007_199_254_740_991L;

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
        @Valid
        @NotNull
        private RpcProviderAdmissionConfig admission = new RpcProviderAdmissionConfig();

        @AssertTrue(message = "provider admission requires the RPC provider to be enabled")
        public boolean isAdmissionActivationValid() {
            return admission == null || !admission.isEnabled() || enabled;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class RpcProviderAdmissionConfig {

        private boolean enabled;
        @Min(1)
        @Max(65535)
        private int maxConcurrent = 64;
        @NotNull
        private Map<@NotBlank @Size(max = 512) String,
                @NotNull @Valid RpcProviderMethodAdmissionConfig> methods = new LinkedHashMap<>();

        @AssertTrue(message = "provider admission requires exact native methods and valid bounded policies")
        public boolean isConfigurationValid() {
            if (maxConcurrent < 1 || maxConcurrent > 65535 || methods == null || enabled && methods.isEmpty()) {
                return false;
            }
            for (var entry : methods.entrySet()) {
                var policy = entry.getValue();
                if (entry.getKey() == null || entry.getKey().length() > 512
                        || !RpcServiceTargetVO.isNativeMethodName(entry.getKey()) || policy == null
                        || !policy.isDimensionsValid()
                        || policy.getMaxAttempts() == 2 && !RpcReferencePolicy.APPROVED_READ_METHOD.equals(entry.getKey())) {
                    return false;
                }
            }
            return true;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class RpcProviderMethodAdmissionConfig {

        @NotBlank
        @Size(max = 256)
        @Pattern(regexp = "[\\x20-\\x7e]+")
        private String quota;
        @Positive
        @Max(MAX_EXACT_LUA_INTEGER)
        private long quotaCapacity;
        @Positive
        @Max(MAX_EXACT_LUA_INTEGER)
        private long quotaCost = 1;
        @Min(1)
        @Max(2)
        private int maxAttempts = 1;
        @NotNull
        private Duration replayWindow = Duration.ofSeconds(5);
        @Positive
        @Max(MAX_EXACT_LUA_INTEGER)
        private long physicalCapacity;
        @NotNull
        private Duration physicalWindow = Duration.ofSeconds(1);

        @AssertTrue(message = "admission dimensions and whole-millisecond windows must be within supported bounds")
        public boolean isDimensionsValid() {
            return quota != null && !quota.isBlank() && quota.length() <= 256
                    && quota.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)
                    && quotaCapacity > 0 && quotaCapacity <= MAX_EXACT_LUA_INTEGER
                    && quotaCost > 0 && quotaCost <= MAX_EXACT_LUA_INTEGER
                    && maxAttempts >= 1 && maxAttempts <= 2
                    && wholeMillisecondsBetween(replayWindow, 1500, 300_000)
                    && physicalCapacity > 0 && physicalCapacity <= MAX_EXACT_LUA_INTEGER
                    && wholeMillisecondsBetween(physicalWindow, 1, 300_000);
        }

        private static boolean wholeMillisecondsBetween(Duration value, long minimum, long maximum) {
            return value != null && value.compareTo(Duration.ofMillis(minimum)) >= 0
                    && value.compareTo(Duration.ofMillis(maximum)) <= 0 && value.getNano() % 1_000_000 == 0;
        }
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
        @NotNull
        private Map<@NotBlank String, @NotNull @Valid RpcRetryApprovalConfig> retryApprovals = new LinkedHashMap<>();

        /** Rejects unused, normalized or broader retry grants during Boot configuration binding. */
        @AssertTrue(message = "retry approvals must name the bound approved read and satisfy its retry budget")
        public boolean isRetryApprovalConfigurationValid() {
            if (retryApprovals == null || bindings == null) {
                return false;
            }
            for (var entry : retryApprovals.entrySet()) {
                var approval = entry.getValue();
                if (!RpcReferencePolicy.APPROVED_READ_METHOD.equals(entry.getKey()) || approval == null
                        || approval.getRetries() < 0 || approval.getRetries() > 1
                        || approval.getAttemptTimeoutMs() <= 0
                        || approval.getAttemptTimeoutMs() > RpcReferencePolicy.MAX_APPROVED_ATTEMPT_TIMEOUT_MS
                        || approval.getTotalTimeoutMs() <= 0
                        || approval.getTotalTimeoutMs() > RpcReferencePolicy.MAX_APPROVED_TOTAL_TIMEOUT_MS
                        || approval.getAttemptTimeoutMs() > approval.getTotalTimeoutMs()) {
                    return false;
                }
                boolean bound = bindings.values().stream().anyMatch(binding -> binding != null
                        && binding.getMethods() != null && binding.getMethods().contains(entry.getKey()));
                if (!bound) {
                    return false;
                }
            }
            return true;
        }
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class RpcRetryApprovalConfig {

        @Min(0)
        @Max(1)
        private int retries;
        @Positive
        @Max(RpcReferencePolicy.MAX_APPROVED_ATTEMPT_TIMEOUT_MS)
        private long attemptTimeoutMs;
        @Positive
        @Max(RpcReferencePolicy.MAX_APPROVED_TOTAL_TIMEOUT_MS)
        private long totalTimeoutMs;
    }
}
