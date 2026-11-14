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
        @Valid SessionSettings session,
        @Valid MemorySettings memory,
        @Valid RagSettings rag) {

    @ConstructorBinding
    public AgentFlowProperties {
        executionTimeout = executionTimeout == null ? Duration.ofMinutes(2) : executionTimeout;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(10) : shutdownTimeout;
        flows = normalizeFlows(flows);
        session = session == null ? new SessionSettings(null) : session;
        memory = memory == null ? new MemorySettings(null, null, null) : memory;
        rag = rag == null ? new RagSettings(false) : rag;
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows) {
        this(enabled, executionTimeout, shutdownTimeout, flows, null, null, null);
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows, SessionSettings session) {
        this(enabled, executionTimeout, shutdownTimeout, flows, session, null, null);
    }

    public record MemorySettings(String mode, String dataSourceRef, String schema) {
        static final String NONE = "NONE";
        static final String IN_MEMORY = "IN_MEMORY";
        static final String JDBC = "JDBC";

        public MemorySettings {
            mode = mode == null ? IN_MEMORY : mode;
            if (!java.util.Set.of(NONE, IN_MEMORY, JDBC).contains(mode)) {
                throw new IllegalArgumentException("Agent Flow memory mode must be NONE, IN_MEMORY, or JDBC");
            }
            if (JDBC.equals(mode)) {
                if (dataSourceRef == null || !dataSourceRef.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                    throw new IllegalArgumentException("Agent Flow JDBC memory requires an explicit valid data-source-ref");
                }
                if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
                    throw new IllegalArgumentException("Agent Flow JDBC memory requires an explicit valid schema");
                }
            } else if (dataSourceRef != null || schema != null) {
                throw new IllegalArgumentException("Agent Flow data-source-ref and schema require JDBC memory mode");
            }
        }
    }

    public record RagSettings(boolean enabled) { }

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
