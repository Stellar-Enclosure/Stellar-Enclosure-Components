package top.egon.cola.component.agentflow.api.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Stable trim reason codes emitted during unified context budget assembly. */
public enum ContextTrimReasonEnum {
    BUDGET_OTHERS("BUDGET_OTHERS"),
    BUDGET_DOC_MEMORY("BUDGET_DOC_MEMORY"),
    BUDGET_TEXT_MEMORY("BUDGET_TEXT_MEMORY"),
    BUDGET_HISTORY_TURN("BUDGET_HISTORY_TURN");

    @JsonValue
    private final String code;

    ContextTrimReasonEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ContextTrimReasonEnum fromCode(Object wire) {
        if (wire instanceof String text) {
            for (ContextTrimReasonEnum candidate : values()) {
                if (candidate.code.equals(text)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown Context trim reason");
    }
}
