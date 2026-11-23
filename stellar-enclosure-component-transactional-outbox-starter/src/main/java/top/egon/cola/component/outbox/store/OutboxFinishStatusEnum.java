package top.egon.cola.component.outbox.store;

import top.egon.cola.component.common.core.enums.EgonEnum;

public enum OutboxFinishStatusEnum implements EgonEnum {
    APPLIED(0),
    ALREADY_TERMINAL(1),
    LEASE_LOST(2),
    INDETERMINATE(3);

    private final int code;

    OutboxFinishStatusEnum(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return name();
    }
}
