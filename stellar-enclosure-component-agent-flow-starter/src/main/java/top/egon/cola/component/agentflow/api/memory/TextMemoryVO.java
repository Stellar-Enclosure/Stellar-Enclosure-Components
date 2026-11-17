package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;

/** Immutable view snapshot of a Level 2 mid-term text memory record. */
public record TextMemoryVO(
        @NotBlank @Size(max = 64) String id,
        @Min(1) long version,
        @NotNull @Size(max = TextMemorySaveCommand.MAX_CONTENT_CHARS) String contentText,
        @PositiveOrZero int charCount,
        @NotNull Instant updatedAt) {

    public TextMemoryVO {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("TextMemoryVO id must be non-blank and <= 64 chars");
        }
        if (version < 1) {
            throw new IllegalArgumentException("TextMemoryVO version must be >= 1");
        }
        Objects.requireNonNull(contentText, "contentText");
        if (contentText.length() > TextMemorySaveCommand.MAX_CONTENT_CHARS || contentText.indexOf(0) >= 0) {
            throw new IllegalArgumentException("TextMemoryVO contentText violates bounds");
        }
        if (charCount != contentText.length()) {
            throw new IllegalArgumentException("TextMemoryVO charCount mismatch");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String toString() {
        return "TextMemoryVO[id=" + id + ", version=" + version + ", charCount=" + charCount
                + ", updatedAt=" + updatedAt + ']';
    }
}
