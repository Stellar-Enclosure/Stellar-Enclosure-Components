package top.egon.cola.component.agentflow.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import top.egon.cola.component.agentflow.config.AgentFlowConfigDTO;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, strictly bound configuration for the Agent Flow starter. */
public record AgentFlowProperties(
        boolean enabled,
        @NotNull Duration executionTimeout,
        @NotNull Duration shutdownTimeout,
        Map<String, @Valid AgentFlowConfigDTO> flows,
        @Valid SessionSettings session) {

    @ConstructorBinding
    public AgentFlowProperties {
        executionTimeout = executionTimeout == null ? Duration.ofMinutes(2) : executionTimeout;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(10) : shutdownTimeout;
        flows = normalizeFlows(flows);
        session = session == null ? new SessionSettings(null) : session;
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows) {
        this(enabled, executionTimeout, shutdownTimeout, flows, null);
    }

    public record SessionSettings(String storeRef) {

        public SessionSettings {
            if (storeRef == null) {
                storeRef = "inMemory";
            } else if (!storeRef.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Agent Flow session store-ref must be a valid bean name");
            }
        }
    }

    private static Map<String, AgentFlowConfigDTO> normalizeFlows(Map<String, AgentFlowConfigDTO> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, AgentFlowConfigDTO> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = key == null ? null : key.trim();
            if (normalized.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("duplicate flow id: " + normalizedKey);
            }
            normalized.put(normalizedKey, value);
        });
        return Map.copyOf(normalized);
    }
}
