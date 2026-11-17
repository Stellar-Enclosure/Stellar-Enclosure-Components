package top.egon.cola.component.agentflow.api.context;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Objects;

/** Immutable observability snapshot of unified context assembly and budget trimming. */
public record ContextAssemblyReportVO(
        @PositiveOrZero int availableInputTokens,
        @PositiveOrZero int estimatedInputTokens,
        @PositiveOrZero int systemTokens,
        @PositiveOrZero int historyTurnsIncluded,
        @PositiveOrZero int historyTurnsTrimmed,
        boolean textMemoryIncluded,
        @PositiveOrZero int textMemoryChars,
        @PositiveOrZero int docCountRequested,
        @PositiveOrZero int docCountIncluded,
        @PositiveOrZero int othersTrimmed,
        @NotNull List<@NotNull @Valid ContextTrimDetailVO> trimDetails) {

    public ContextAssemblyReportVO {
        if (availableInputTokens < 0 || estimatedInputTokens < 0 || systemTokens < 0
                || historyTurnsIncluded < 0 || historyTurnsTrimmed < 0
                || textMemoryChars < 0 || docCountRequested < 0
                || docCountIncluded < 0 || othersTrimmed < 0) {
            throw new IllegalArgumentException("ContextAssemblyReportVO counts must be non-negative");
        }
        Objects.requireNonNull(trimDetails, "trimDetails");
        if (trimDetails.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("trimDetails must not contain null");
        }
        trimDetails = List.copyOf(trimDetails);
    }

    @Override
    public String toString() {
        return "ContextAssemblyReportVO[availableInputTokens=" + availableInputTokens
                + ", estimatedInputTokens=" + estimatedInputTokens
                + ", systemTokens=" + systemTokens
                + ", historyTurnsIncluded=" + historyTurnsIncluded
                + ", historyTurnsTrimmed=" + historyTurnsTrimmed
                + ", textMemoryIncluded=" + textMemoryIncluded
                + ", textMemoryChars=" + textMemoryChars
                + ", docCountRequested=" + docCountRequested
                + ", docCountIncluded=" + docCountIncluded
                + ", othersTrimmed=" + othersTrimmed
                + ", trimDetailCount=" + trimDetails.size() + ']';
    }

    /** Single trimmed context unit without exposing sensitive payload text. */
    public record ContextTrimDetailVO(
            @NotNull ContextTrimReasonEnum reason,
            @NotBlank String sectionId,
            @PositiveOrZero int estimatedTokens) {

        public ContextTrimDetailVO {
            Objects.requireNonNull(reason, "reason");
            ContextScopeVO.requireIdentifier(sectionId);
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens must be non-negative");
            }
        }
    }
}
