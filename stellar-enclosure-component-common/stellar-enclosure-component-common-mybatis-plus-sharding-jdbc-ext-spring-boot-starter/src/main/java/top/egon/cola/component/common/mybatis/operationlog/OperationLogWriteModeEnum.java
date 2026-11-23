package top.egon.cola.component.common.mybatis.operationlog;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import top.egon.cola.component.common.core.enums.EgonEnum;

@Getter
@RequiredArgsConstructor
public enum OperationLogWriteModeEnum implements EgonEnum {
    SOURCE_LOCAL(0, "SOURCE_LOCAL"),
    OUTBOX(1, "OUTBOX");

    private final int code;
    private final String message;
}
