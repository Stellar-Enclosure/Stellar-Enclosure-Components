package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

import java.time.Instant;
import java.util.Objects;

/** Immutable Level 3 Markdown long-term memory document snapshot. */
public record DocMemoryVO(
        @NotBlank String id,
        @NotNull String title,
        @NotNull String contentMd,
        String contentSha256,
        @NotNull Instant updatedAt) {

    public DocMemoryVO {
        ContextScopeVO.requireIdentifier(id);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(contentMd, "contentMd");
        if (title.indexOf(0) >= 0 || contentMd.indexOf(0) >= 0) {
            throw new IllegalArgumentException("DocMemoryVO text must not contain NUL bytes");
        }
        if (contentSha256 != null && !contentSha256.isBlank() && !contentSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("DocMemoryVO contentSha256 must be lowercase 64-hex SHA-256");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String toString() {
        return "DocMemoryVO[id=" + id + ", titleChars=" + title.length()
                + ", contentChars=" + contentMd.length() + ", updatedAt=" + updatedAt + ']';
    }
}
