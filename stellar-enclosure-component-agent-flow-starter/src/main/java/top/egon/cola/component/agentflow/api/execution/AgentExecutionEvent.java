package top.egon.cola.component.agentflow.api.execution;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AgentExecutionEvent(
        String executionId, @Positive long sequence, @NotNull TypeEnum type,
        @NotNull Instant occurredAt, @NotNull Map<String, Object> payload) {

    public AgentExecutionEvent {
        ContextScopeVO.requireIdentifier(executionId);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence <= 0) {
            throw new IllegalArgumentException("Invalid execution event sequence");
        }
        payload = AgentExecutionCommand.JsonValues.copyObject(payload, AgentExecutionCommand.MAX_OUTPUT_BYTES);
    }

    public enum TypeEnum {
        PROGRESS("PROGRESS"),
        TOOL_STARTED("TOOL_STARTED"),
        TOOL_FINISHED("TOOL_FINISHED"),
        OUTPUT("OUTPUT"),
        TERMINAL("TERMINAL");

        @JsonValue
        private final String code;

        TypeEnum(String code) {
            this.code = code;
        }
    }
}
