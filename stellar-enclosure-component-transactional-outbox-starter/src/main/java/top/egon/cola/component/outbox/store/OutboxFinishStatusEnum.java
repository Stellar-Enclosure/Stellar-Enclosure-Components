package top.egon.cola.component.outbox.store;

import top.egon.cola.component.common.core.enums.EgonEnum;

public enum OutboxFinishStatusEnum implements EgonEnum {
    APPLIED(0, "APPLIED"),
    ALREADY_TERMINAL(1, "ALREADY_TERMINAL"),
    LEASE_LOST(2, "LEASE_LOST"),
    INDETERMINATE(3, "INDETERMINATE");

    private final int code;
    private final String message;

    OutboxFinishStatusEnum(int code, String message) {
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
