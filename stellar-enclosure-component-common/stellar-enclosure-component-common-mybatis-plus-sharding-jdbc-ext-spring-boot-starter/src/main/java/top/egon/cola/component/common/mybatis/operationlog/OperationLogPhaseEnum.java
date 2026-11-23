package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum OperationLogPhaseEnum {
    COMMAND("COMMAND"),
    EXECUTION("EXECUTION");

    @EnumValue
    @JsonValue
    private final String code;
}
