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
        @NotNull CallPermitProvider callPermitProvider,
        ModelCallLifecycle modelCallLifecycle) {

    public AgentExecutionContextVO(ContextScopeVO scope, Instant deadline, CompletionStage<Void> cancellation,
                                   ContextSource contextSource, SessionStore sessionStore, CallPermitProvider callPermitProvider) {
        this(scope, deadline, cancellation, contextSource, sessionStore, callPermitProvider, null);
    }

    /** Invoked after prompt validation and before the provider is contacted. */
    @FunctionalInterface
    @JsonIgnoreType
    public interface ModelCallLifecycle {
        ModelCall begin(ModelCallRequestVO request);
    }

    @FunctionalInterface
    @JsonIgnoreType
    public interface ModelCall {
        /** Reports a known outcome while the execution is current. Cancellation may leave this call unclosed. */
        void finish(ModelCallOutcomeVO outcome);
    }

    @JsonIgnoreType
    public record ModelCallRequestVO(String executionId, ContextScopeVO scope, String modelRef,
                                     String inputSha256, Instant deadline) {
        public ModelCallRequestVO {
            ContextScopeVO.requireIdentifier(executionId);
            ContextScopeVO.requireIdentifier(modelRef);
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(deadline, "deadline");
            if (!validDigest(inputSha256)) throw new IllegalArgumentException("Invalid model request digest");
        }
    }

    @JsonIgnoreType
    public record ModelCallOutcomeVO(String resultSha256, Integer inputTokens, Integer outputTokens, String errorCode) {
        public ModelCallOutcomeVO {
            if ((errorCode == null && !validDigest(resultSha256))
                    || (errorCode != null && (resultSha256 != null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")))
                    || (inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
                throw new IllegalArgumentException("Invalid model outcome");
            }
        }
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

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
