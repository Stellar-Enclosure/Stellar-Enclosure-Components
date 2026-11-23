package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import top.egon.cola.component.common.core.enums.EgonEnum;

@Getter
@RequiredArgsConstructor
public enum OperationLogOutcomeEnum implements EgonEnum {
    ACCEPTED(0, "ACCEPTED"),
    SUCCEEDED(1, "SUCCEEDED"),
    FAILED(2, "FAILED"),
    PARTIAL_SUCCESS(3, "PARTIAL_SUCCESS"),
    UNKNOWN(4, "UNKNOWN"),
    REJECTED(5, "REJECTED"),
    ROLLED_BACK(6, "ROLLED_BACK");

    private final int code;
    @EnumValue
    @JsonValue
    private final String message;
}
