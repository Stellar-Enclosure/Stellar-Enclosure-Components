package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.nio.charset.StandardCharsets;

/** Command to update Level 2 mid-term text memory with strict length and optimistic-lock checks. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class TextMemorySaveCommand {

    public static final int MAX_CONTENT_CHARS = 10_000;

    @NotBlank
    @Size(max = 128)
    private String tenantKey;

    @NotBlank
    @Size(max = 64)
    private String id;

    @NotNull
    @Size(max = MAX_CONTENT_CHARS)
    private String contentText;

    @Min(1)
    private long expectedVersion;

    /** Validates boundaries strictly without truncating oversized text. */
    public TextMemorySaveCommand snapshot() {
        return snapshot(MAX_CONTENT_CHARS);
    }

    /** Validates boundaries against a configured ceiling (never exceeding {@link #MAX_CONTENT_CHARS}). */
    public TextMemorySaveCommand snapshot(int configuredMaxChars) {
        ContextScopeVO.requireIdentifier(tenantKey);
        if (id == null || id.isBlank() || id.length() > 64
                || id.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(id)) {
            throw new IllegalArgumentException("Invalid TextMemory id");
        }
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be >= 1");
        }
        if (contentText == null) {
            throw new IllegalArgumentException("contentText must not be null");
        }
        if (contentText.indexOf(0) >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(contentText)) {
            throw new IllegalArgumentException("Invalid TextMemory content encoding");
        }
        int effectiveMax = Math.min(Math.max(1, configuredMaxChars), MAX_CONTENT_CHARS);
        if (contentText.length() > effectiveMax) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "TEXT_MEMORY_TOO_LARGE", "TEXT_MEMORY_TOO_LARGE");
        }
        return new TextMemorySaveCommand(tenantKey, id, contentText, expectedVersion);
    }
}
