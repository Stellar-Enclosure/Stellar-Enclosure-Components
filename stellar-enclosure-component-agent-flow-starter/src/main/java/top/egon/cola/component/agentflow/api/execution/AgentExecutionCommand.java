package top.egon.cola.component.agentflow.api.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class AgentExecutionCommand {

    public static final int MAX_INPUT_BYTES = 64 * 1024;
    public static final int MAX_OUTPUT_BYTES = 8 * 1024;

    @NotBlank @Size(max = 128)
    private String executionId;

    @NotNull @Valid
    private TemplateVO template;

    @NotNull @Valid
    private Map<String, Object> input;

    @NotNull @Valid
    @Builder.Default
    private Map<String, Object> parameters = Map.of();

    @NotNull @Valid
    private TechnicalLimitsVO limits;

    public record TemplateVO(String key, long version, String contentSha256, String executorRef,
                             Map<String, Object> configuration, @JsonIgnore SchemaValidator schemaValidator) {

        public TemplateVO {
            ContextScopeVO.requireIdentifier(key);
            ContextScopeVO.requireIdentifier(executorRef);
            if (version < 1 || version > JsonValues.MAX_EXACT_INTEGER
                    || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid published template reference");
            }
            configuration = JsonValues.copyObject(configuration, 1024 * 1024);
            Objects.requireNonNull(schemaValidator, "schemaValidator");
        }
    }

    /** Validates against the exact published schemas resolved by the trusted host. */
    public interface SchemaValidator {

        void validateInput(Map<String, Object> input, Map<String, Object> parameters);

        void validateOutput(Map<String, Object> output);
    }

    public record TechnicalLimitsVO(Duration totalTimeout, Duration nodeTimeout, Duration modelIdleTimeout,
                                    Duration toolTimeout, int maxModelCalls, int maxToolCalls,
                                    int maxParallelism, int contextWindowTokens, int maxOutputTokensPerCall) {

        public TechnicalLimitsVO {
            requirePositive(totalTimeout);
            requirePositive(nodeTimeout);
            requirePositive(modelIdleTimeout);
            requirePositive(toolTimeout);
            if (totalTimeout.compareTo(nodeTimeout) > 0 || modelIdleTimeout.compareTo(totalTimeout) > 0
                    || toolTimeout.compareTo(totalTimeout) > 0
                    || !within(maxModelCalls, 1000) || !within(maxToolCalls, 1000)
                    || !within(maxParallelism, 32) || !within(contextWindowTokens, 1_048_576)
                    || !within(maxOutputTokensPerCall, Math.min(65_536, contextWindowTokens))) {
                throw new IllegalArgumentException("Invalid agent execution limits");
            }
        }

        private static void requirePositive(Duration duration) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("Execution time limits must be positive");
            }
        }

        private static boolean within(int value, int maximum) {
            return value > 0 && value <= maximum;
        }
    }

    /** Bounded immutable JSON values shared by neutral in-process contracts. */
    public static final class JsonValues {

        private static final long MAX_EXACT_INTEGER = 999_999_999_999_999L;
        private static final ObjectMapper MAPPER = JsonMapper.builder()
                .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
                .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
                .build();

        private JsonValues() {
        }

        public static Map<String, Object> copyObject(Map<String, ?> value, int maxBytes) {
            Objects.requireNonNull(value, "JSON object");
            @SuppressWarnings("unchecked")
            var frozen = (Map<String, Object>) freeze(value, 0, new Budget(maxBytes));
            if (canonicalBytes(frozen).length > maxBytes) {
                throw new IllegalArgumentException("Agent Flow JSON exceeds its byte limit");
            }
            return frozen;
        }

        public static byte[] canonicalBytes(Map<String, ?> frozenValue) {
            try {
                return MAPPER.writeValueAsBytes(frozenValue);
            } catch (JsonProcessingException failure) {
                throw new IllegalArgumentException("Invalid Agent Flow JSON", failure);
            }
        }

        private static Object freeze(Object value, int depth, Budget budget) {
            if (depth > 8) {
                throw new IllegalArgumentException("Agent Flow JSON exceeds its depth limit");
            }
            budget.take(1);
            if (value == null || value instanceof Boolean) {
                return value;
            }
            if (value instanceof String text) {
                checkText(text, budget);
                return text;
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger) {
                long exact;
                try {
                    exact = value instanceof BigInteger integer ? integer.longValueExact() : ((Number) value).longValue();
                } catch (ArithmeticException failure) {
                    throw new IllegalArgumentException("Agent Flow JSON integer exceeds its range", failure);
                }
                if (exact < -MAX_EXACT_INTEGER || exact > MAX_EXACT_INTEGER) {
                    throw new IllegalArgumentException("Agent Flow JSON integer exceeds its range");
                }
                return exact;
            }
            if (value instanceof Map<?, ?> source) {
                Map<String, Object> result = new TreeMap<>();
                for (var entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Agent Flow JSON object requires string keys");
                    }
                    checkText(key, budget);
                    result.put(key, freeze(entry.getValue(), depth + 1, budget));
                }
                return Collections.unmodifiableMap(new LinkedHashMap<>(result));
            }
            if (value instanceof List<?> source) {
                if (source.size() > budget.remaining) {
                    throw new IllegalArgumentException("Agent Flow JSON exceeds its byte limit");
                }
                List<Object> result = new ArrayList<>(source.size());
                for (Object item : source) {
                    result.add(freeze(item, depth + 1, budget));
                }
                return Collections.unmodifiableList(result);
            }
            throw new IllegalArgumentException("Agent Flow JSON requires bounded JSON values");
        }

        private static void checkText(String text, Budget budget) {
            if (text.length() > budget.remaining || text.indexOf(0) >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
                throw new IllegalArgumentException("Invalid Agent Flow JSON text");
            }
            budget.take(text.getBytes(StandardCharsets.UTF_8).length);
        }

        private static final class Budget {

            private int remaining;

            private Budget(int remaining) {
                this.remaining = remaining;
            }

            private void take(int amount) {
                remaining -= amount;
                if (remaining < 0) {
                    throw new IllegalArgumentException("Agent Flow JSON exceeds its byte limit");
                }
            }
        }
    }
}
