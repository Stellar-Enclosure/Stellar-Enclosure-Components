package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum OperationLogOutcomeEnum {
    ACCEPTED("ACCEPTED"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    PARTIAL_SUCCESS("PARTIAL_SUCCESS"),
    UNKNOWN("UNKNOWN"),
    REJECTED("REJECTED"),
    ROLLED_BACK("ROLLED_BACK");

    @EnumValue
    @JsonValue
    private final String code;
}
