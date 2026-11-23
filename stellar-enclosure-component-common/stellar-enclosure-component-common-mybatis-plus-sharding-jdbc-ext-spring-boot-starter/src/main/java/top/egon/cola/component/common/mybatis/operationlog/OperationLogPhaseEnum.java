package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import top.egon.cola.component.common.core.enums.EgonEnum;

@Getter
@RequiredArgsConstructor
public enum OperationLogPhaseEnum implements EgonEnum {
    COMMAND(0, "COMMAND"),
    EXECUTION(1, "EXECUTION");

    private final int code;
    @EnumValue
    @JsonValue
    private final String message;
}
