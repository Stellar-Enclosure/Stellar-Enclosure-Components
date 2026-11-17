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
        @Valid RagSettings rag,
        @Valid LegacyStaticWorkflowSettings legacyStaticWorkflow,
        @Valid ContextSettings context) {

    @ConstructorBinding
    public AgentFlowProperties {
        executionTimeout = executionTimeout == null ? Duration.ofMinutes(2) : executionTimeout;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(10) : shutdownTimeout;
        flows = normalizeFlows(flows);
        session = session == null ? new SessionSettings(null) : session;
        memory = memory == null ? new MemorySettings(null, null, null) : memory;
        rag = rag == null ? new RagSettings(false) : rag;
        legacyStaticWorkflow = legacyStaticWorkflow == null
                ? new LegacyStaticWorkflowSettings(true)
                : legacyStaticWorkflow;
        context = context == null ? new ContextSettings(null, null, null) : context;
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows) {
        this(enabled, executionTimeout, shutdownTimeout, flows, null, null, null, null, null);
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows, SessionSettings session) {
        this(enabled, executionTimeout, shutdownTimeout, flows, session, null, null, null, null);
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows, SessionSettings session,
                               MemorySettings memory, RagSettings rag) {
        this(enabled, executionTimeout, shutdownTimeout, flows, session, memory, rag, null, null);
    }

    public AgentFlowProperties(boolean enabled, Duration executionTimeout, Duration shutdownTimeout,
                               Map<String, AgentFlowConfigDTO> flows, SessionSettings session,
                               MemorySettings memory, RagSettings rag,
                               LegacyStaticWorkflowSettings legacyStaticWorkflow) {
        this(enabled, executionTimeout, shutdownTimeout, flows, session, memory, rag, legacyStaticWorkflow, null);
    }

    /** 遗留静态多智能体工作流策略开关（默认启用以保持老业务兼容）。 */
    public record LegacyStaticWorkflowSettings(Boolean enabled) {

        public LegacyStaticWorkflowSettings {
            enabled = enabled == null || enabled;
        }
    }

    /** Three-tier memory rollout toggle (disabled by default to preserve V1-only host compatibility). */
    public record ThreeTierSettings(Boolean enabled) {

        public ThreeTierSettings {
            enabled = Boolean.TRUE.equals(enabled);
        }
    }

    /** Whitelisted schema/column settings for Level 3 host-managed doc_memory lookups. */
    public record DocSettings(
            String tableName,
            String idColumn,
            String tenantColumn,
            String titleColumn,
            String contentColumn,
            String sha256Column,
            String deletedColumn,
            String updatedAtColumn,
            Integer maxBatchSize) {

        public DocSettings {
            tableName = requireSqlIdentifier(tableName == null ? "doc_memory" : tableName, "table-name");
            idColumn = requireSqlIdentifier(idColumn == null ? "id" : idColumn, "id-column");
            tenantColumn = requireSqlIdentifier(tenantColumn == null ? "tenant_key" : tenantColumn, "tenant-column");
            titleColumn = requireSqlIdentifier(titleColumn == null ? "title" : titleColumn, "title-column");
            contentColumn = requireSqlIdentifier(contentColumn == null ? "content_md" : contentColumn, "content-column");
            sha256Column = requireSqlIdentifier(sha256Column == null ? "content_sha256" : sha256Column, "sha256-column");
            deletedColumn = requireSqlIdentifier(deletedColumn == null ? "deleted" : deletedColumn, "deleted-column");
            updatedAtColumn = requireSqlIdentifier(updatedAtColumn == null ? "updated_at" : updatedAtColumn, "updated-at-column");
            maxBatchSize = maxBatchSize == null ? 32 : maxBatchSize;
            if (maxBatchSize < 1 || maxBatchSize > 32) {
                throw new IllegalArgumentException("Agent Flow memory doc max-batch-size must be between 1 and 32");
            }
        }

        public static DocSettings defaults() {
            return new DocSettings(null, null, null, null, null, null, null, null, null);
        }
    }

    public record MemorySettings(
            String mode,
            String dataSourceRef,
            String schema,
            @Valid ThreeTierSettings threeTier,
            Integer sessionWindowTurns,
            Integer textMaxChars,
            @Valid DocSettings doc) {
        public static final int DEFAULT_SESSION_WINDOW_TURNS = 20;
        public static final int MAX_SESSION_WINDOW_TURNS = 200;
        public static final int DEFAULT_TEXT_MAX_CHARS = 10_000;
        static final String NONE = "NONE";
        static final String IN_MEMORY = "IN_MEMORY";
        static final String JDBC = "JDBC";

        @ConstructorBinding
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
            threeTier = threeTier == null ? new ThreeTierSettings(false) : threeTier;
            sessionWindowTurns = sessionWindowTurns == null ? DEFAULT_SESSION_WINDOW_TURNS : sessionWindowTurns;
            if (sessionWindowTurns < 1 || sessionWindowTurns > MAX_SESSION_WINDOW_TURNS) {
                throw new IllegalArgumentException(
                        "Agent Flow memory session-window-turns must be between 1 and " + MAX_SESSION_WINDOW_TURNS);
            }
            textMaxChars = textMaxChars == null ? DEFAULT_TEXT_MAX_CHARS : textMaxChars;
            if (textMaxChars < 1 || textMaxChars > DEFAULT_TEXT_MAX_CHARS) {
                throw new IllegalArgumentException(
                        "Agent Flow memory text-max-chars must be between 1 and " + DEFAULT_TEXT_MAX_CHARS);
            }
            doc = doc == null ? DocSettings.defaults() : doc;
        }

        public MemorySettings(String mode, String dataSourceRef, String schema) {
            this(mode, dataSourceRef, schema, null, null, null, null);
        }

        public boolean threeTierEnabled() {
            return Boolean.TRUE.equals(threeTier.enabled());
        }
    }

    /** Unified context assembly and token budget defaults. */
    public record ContextSettings(
            Integer safetyMarginTokens,
            Integer minKeepHistoryTurns,
            Boolean combineContextIntoFinalUserMessage) {

        public ContextSettings {
            safetyMarginTokens = safetyMarginTokens == null ? 512 : safetyMarginTokens;
            if (safetyMarginTokens < 0) {
                throw new IllegalArgumentException("Agent Flow context safety-margin-tokens must be >= 0");
            }
            minKeepHistoryTurns = minKeepHistoryTurns == null ? 2 : minKeepHistoryTurns;
            if (minKeepHistoryTurns < 0) {
                throw new IllegalArgumentException("Agent Flow context min-keep-history-turns must be >= 0");
            }
            combineContextIntoFinalUserMessage = combineContextIntoFinalUserMessage == null
                    || combineContextIntoFinalUserMessage;
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

    private static String requireSqlIdentifier(String candidate, String propertyName) {
        if (candidate == null || !candidate.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Agent Flow invalid SQL identifier for " + propertyName);
        }
        return candidate;
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
