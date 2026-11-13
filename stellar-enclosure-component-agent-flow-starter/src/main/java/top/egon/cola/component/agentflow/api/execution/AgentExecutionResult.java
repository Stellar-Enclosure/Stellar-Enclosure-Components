package top.egon.cola.component.agentflow.api.execution;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AgentExecutionResult(
        String executionId, @NotNull StatusEnum status, Map<String, Object> output, String errorCode,
        @NotNull SideEffectOutcomeEnum sideEffectOutcome, @NotNull Instant finishedAt) {

    public AgentExecutionResult {
        ContextScopeVO.requireIdentifier(executionId);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sideEffectOutcome, "sideEffectOutcome");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (status == StatusEnum.COMPLETED) {
            if (errorCode != null || output == null) {
                throw new IllegalArgumentException("Completed execution requires output and no error");
            }
            output = AgentExecutionCommand.JsonValues.copyObject(output, AgentExecutionCommand.MAX_OUTPUT_BYTES);
        } else if (output != null || errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Non-completed execution requires a stable error code");
        }
    }

    public enum StatusEnum {
        COMPLETED("COMPLETED"),
        FAILED("FAILED"),
        CANCELLED("CANCELLED"),
        TIMED_OUT("TIMED_OUT");

        @JsonValue
        private final String code;

        StatusEnum(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public enum SideEffectOutcomeEnum {
        NONE("NONE"),
        CONFIRMED("CONFIRMED"),
        UNKNOWN("UNKNOWN");

        @JsonValue
        private final String code;

        SideEffectOutcomeEnum(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
