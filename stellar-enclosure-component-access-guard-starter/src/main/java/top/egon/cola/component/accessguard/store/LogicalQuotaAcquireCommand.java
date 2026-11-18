package top.egon.cola.component.accessguard.store;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Duration;

/** Trusted logical-operation admission input; its scope strings retain their exact identity. */
public record LogicalQuotaAcquireCommand(
        @Positive long tenantId,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\x20-\\x7e]+") String clientId,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\x20-\\x7e]+") String resource,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\x20-\\x7e]+") String quota,
        @NotBlank @Size(max = 512) @Pattern(regexp = "[\\x20-\\x7e]+") String fullMethodName,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[\\x20-\\x7e]+") String logicalCallId,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String requestDigest,
        @Min(1) @Max(2) int attempt,
        @Min(1) @Max(2) int maxAttempts,
        @Positive @Max(LogicalQuotaAcquireCommand.MAX_EXACT_LUA_INTEGER) long capacity,
        @Positive @Max(LogicalQuotaAcquireCommand.MAX_EXACT_LUA_INTEGER) long cost,
        @NotNull Duration replayWindow
) {

    public static final long MAX_EXACT_LUA_INTEGER = 9_007_199_254_740_991L;
    private static final Duration MIN_REPLAY_WINDOW = Duration.ofMillis(1500);
    private static final Duration MAX_REPLAY_WINDOW = Duration.ofMillis(300_000);

    public LogicalQuotaAcquireCommand {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        requireScope(clientId, 256, "clientId");
        requireScope(resource, 256, "resource");
        requireScope(quota, 256, "quota");
        requireScope(fullMethodName, 512, "fullMethodName");
        requireScope(logicalCallId, 128, "logicalCallId");
        if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 value");
        }
        if (attempt < 1 || attempt > 2 || maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("attempt and maxAttempts must be between 1 and 2");
        }
        if (capacity <= 0 || capacity > MAX_EXACT_LUA_INTEGER || cost <= 0 || cost > MAX_EXACT_LUA_INTEGER) {
            throw new IllegalArgumentException("capacity and cost must be positive exact Lua integers");
        }
        if (!validReplayWindow(replayWindow)) {
            throw new IllegalArgumentException("replayWindow must be whole milliseconds between 1500 and 300000");
        }
    }

    @AssertTrue(message = "replayWindow must be whole milliseconds between 1500 and 300000")
    public boolean isReplayWindowValid() {
        return validReplayWindow(replayWindow);
    }

    private static boolean validReplayWindow(Duration value) {
        return value != null && value.compareTo(MIN_REPLAY_WINDOW) >= 0
                && value.compareTo(MAX_REPLAY_WINDOW) <= 0 && value.getNano() % 1_000_000 == 0;
    }

    private static void requireScope(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)) {
            throw new IllegalArgumentException(field + " must be bounded nonblank printable ASCII");
        }
    }

    @Override
    public String toString() {
        return "LogicalQuotaAcquireCommand[scope=<redacted>, requestDigest=<redacted>, attempt=" + attempt
                + ", maxAttempts=" + maxAttempts + ", capacity=" + capacity + ", cost=" + cost
                + ", replayWindow=" + replayWindow + "]";
    }
}
