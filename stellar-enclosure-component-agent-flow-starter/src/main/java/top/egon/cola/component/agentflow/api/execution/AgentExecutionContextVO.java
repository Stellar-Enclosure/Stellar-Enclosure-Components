package top.egon.cola.component.agentflow.api.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.capability.CallPermitProvider;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.session.SessionStore;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Trusted, in-process execution context. */
@JsonIgnoreType
public record AgentExecutionContextVO(
        @Valid @NotNull ContextScopeVO scope,
        @NotNull Instant deadline,
        @NotNull CompletionStage<Void> cancellation,
        @NotNull ContextSource contextSource,
        SessionStore sessionStore,
        @NotNull CallPermitProvider callPermitProvider) {

    public AgentExecutionContextVO {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(contextSource, "contextSource");
        Objects.requireNonNull(callPermitProvider, "callPermitProvider");
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentExecutionContextVO rejectJsonInput(Object ignored) {
        throw new IllegalArgumentException("Agent Flow host objects cannot be deserialized");
    }

    @JsonValue
    public Object rejectJsonOutput() {
        throw new IllegalStateException("Agent Flow host objects cannot be serialized");
    }
}
