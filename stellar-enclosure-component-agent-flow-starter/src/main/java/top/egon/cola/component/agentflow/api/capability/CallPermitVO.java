package top.egon.cola.component.agentflow.api.capability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A host-issued capability decision. The callbacks are strictly in-process. */
@JsonIgnoreType
public record CallPermitVO(
        @NotBlank String permitId,
        @NotBlank String capabilityRef,
        @NotBlank String scopeHash,
        @Positive long revision,
        @Positive Long authorityEpoch,
        @NotNull Instant notAfter,
        @NotNull BooleanSupplier cancelState,
        @NotNull Recheck recheck) {

    public CallPermitVO {
        ContextScopeVO.requireIdentifier(permitId);
        ContextScopeVO.requireIdentifier(capabilityRef);
        if (scopeHash == null || !scopeHash.matches("[0-9a-f]{64}") || revision <= 0
                || (authorityEpoch != null && authorityEpoch <= 0)) {
            throw new IllegalArgumentException("Invalid Agent Flow permit binding");
        }
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(cancelState, "cancelState");
        Objects.requireNonNull(recheck, "recheck");
    }

    public void requireCurrent(ContextScopeVO scope, String capability, String purpose, Clock clock) {
        requireCurrent(scope.scopeHash(), capability, purpose, false, clock);
    }

    public void requireCurrent(String expectedScopeHash, String capability, String purpose, boolean memory, Clock clock) {
        ContextScopeVO.requireIdentifier(purpose);
        if (!scopeHash.equals(expectedScopeHash) || !capabilityRef.equals(capability)
                || memory != (authorityEpoch != null)) {
            throw failure("PERMIT_MISMATCH");
        }
        requireLive(clock);
        if (!recheck.isCurrent(this, purpose)) {
            throw failure("PERMIT_REVOKED");
        }
        requireLive(clock);
    }

    private void requireLive(Clock clock) {
        if (!clock.instant().isBefore(notAfter) || cancelState.getAsBoolean()) {
            throw failure("PERMIT_EXPIRED");
        }
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }

    @FunctionalInterface
    public interface Recheck {

        /** Read the current local lease/grant snapshot without network I/O or callbacks into stores. */
        boolean isCurrent(CallPermitVO permit, String purpose);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CallPermitVO rejectJsonInput(Object ignored) {
        throw new IllegalArgumentException("Agent Flow host objects cannot be deserialized");
    }

    @JsonValue
    public Object rejectJsonOutput() {
        throw new IllegalStateException("Agent Flow host objects cannot be serialized");
    }
}
