package top.egon.cola.component.agentflow.api.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.TreeMap;

import java.util.Map;

public record MemoryScopeVO(String namespace, String tenantKey, RetentionScopeEnum retentionScope,
                            String principalKey, String instanceKey, String sessionKey, String hostScopeKey) {

    public MemoryScopeVO {
        ContextScopeVO.requireIdentifier(namespace);
        ContextScopeVO.requireIdentifier(tenantKey);
        retentionScope = retentionScope == null ? RetentionScopeEnum.INSTANCE : retentionScope;
        if (retentionScope == RetentionScopeEnum.HOST) {
            require(principalKey == null && instanceKey == null && sessionKey == null);
            require(hostScopeKey != null && !hostScopeKey.isEmpty() && hostScopeKey.length() <= 2048
                    && StandardCharsets.US_ASCII.newEncoder().canEncode(hostScopeKey)
                    && hostScopeKey.chars().allMatch(value -> value >= 33 && value <= 126));
        } else {
            require(hostScopeKey == null);
            ContextScopeVO.requireIdentifier(principalKey);
            switch (retentionScope) {
                case SESSION -> {
                    ContextScopeVO.requireIdentifier(instanceKey);
                    ContextScopeVO.requireIdentifier(sessionKey);
                }
                case INSTANCE -> {
                    ContextScopeVO.requireIdentifier(instanceKey);
                    require(sessionKey == null);
                }
                case USER -> require(instanceKey == null && sessionKey == null);
                case HOST -> throw new IllegalStateException("Invalid Memory scope kind");
            }
        }
    }

    public Map<String, Object> canonicalScope() {
        Map<String, Object> values = new TreeMap<>();
        values.put("namespace", namespace);
        values.put("tenantKey", tenantKey);
        values.put("retentionScope", retentionScope.code());
        values.put("principalKey", principalKey);
        values.put("instanceKey", instanceKey);
        values.put("sessionKey", sessionKey);
        values.put("hostScopeKey", hostScopeKey);
        return Collections.unmodifiableMap(values);
    }

    public String hash() {
        return MemoryStore.NativeCodec.digest("EGON_AGENT_MEMORY_SCOPE_V1", canonicalScope());
    }

    @Override
    public String toString() {
        return "MemoryScopeVO[retentionScope=" + retentionScope + "]";
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid Memory scope shape");
        }
    }

    public enum RetentionScopeEnum {
        SESSION("SESSION"), INSTANCE("INSTANCE"), USER("USER"), HOST("HOST");

        @JsonValue
        private final String code;

        RetentionScopeEnum(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static RetentionScopeEnum fromCode(Object wire) {
            if (wire instanceof String text) {
                for (RetentionScopeEnum candidate : values()) {
                    if (candidate.code.equals(text)) {
                        return candidate;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown Memory retention scope");
        }
    }
}
