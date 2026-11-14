package top.egon.cola.component.agentflow.api.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryFactTypeEnum {
    FACT("FACT"), PREFERENCE("PREFERENCE"), EXPERIENCE("EXPERIENCE");

    @JsonValue
    private final String code;

    MemoryFactTypeEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MemoryFactTypeEnum fromCode(Object wire) {
        if (wire instanceof String text) {
            for (MemoryFactTypeEnum candidate : values()) {
                if (candidate.code.equals(text)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown Memory fact type");
    }
}
