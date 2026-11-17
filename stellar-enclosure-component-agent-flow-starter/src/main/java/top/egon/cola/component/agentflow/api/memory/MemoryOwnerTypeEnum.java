package top.egon.cola.component.agentflow.api.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Ownership dimension for Level 2 text memory bindings. */
public enum MemoryOwnerTypeEnum {
    AGENT("AGENT"),
    SESSION("SESSION"),
    CUSTOM("CUSTOM");

    @JsonValue
    private final String code;

    MemoryOwnerTypeEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MemoryOwnerTypeEnum fromCode(Object wire) {
        if (wire instanceof String text) {
            for (MemoryOwnerTypeEnum candidate : values()) {
                if (candidate.code.equals(text)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown Memory owner type");
    }
}
