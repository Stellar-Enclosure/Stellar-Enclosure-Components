package top.egon.cola.component.accessguard.store;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import top.egon.cola.component.common.core.enums.EgonEnum;

/** Atomic quota result; zero acceptedAttempt means no attempt has been accepted. */
public record LogicalQuotaDecisionVO(
        boolean allowed,
        @NotNull Reason reason,
        @PositiveOrZero @Max(LogicalQuotaAcquireCommand.MAX_EXACT_LUA_INTEGER) long remaining,
        @Min(0) @Max(2) int acceptedAttempt
) {

    public LogicalQuotaDecisionVO {
        if (reason == null) {
            throw new IllegalArgumentException("reason is required");
        }
        if (allowed != (reason == Reason.ALLOWED || reason == Reason.REPLAY_ALLOWED)) {
            throw new IllegalArgumentException("allowed must match the quota decision reason");
        }
        if (remaining < 0 || remaining > LogicalQuotaAcquireCommand.MAX_EXACT_LUA_INTEGER) {
            throw new IllegalArgumentException("remaining must be a nonnegative exact Lua integer");
        }
        if (acceptedAttempt < 0 || acceptedAttempt > 2) {
            throw new IllegalArgumentException("acceptedAttempt must be between 0 and 2");
        }
        if (allowed != (acceptedAttempt > 0)) {
            throw new IllegalArgumentException("acceptedAttempt must identify an admitted physical attempt");
        }
    }

    public enum Reason implements EgonEnum {
        ALLOWED(0, "allowed"),
        REPLAY_ALLOWED(1, "replay-allowed"),
        EXHAUSTED(2, "exhausted"),
        REPLAY_DENIED(3, "replay-denied"),
        CONFLICT(4, "conflict"),
        WINDOW_EXPIRED(5, "window-expired"),
        ATTEMPT_REJECTED(6, "attempt-rejected"),
        POLICY_MISMATCH(7, "policy-mismatch");

        private final int code;
        private final String message;

        Reason(int code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
