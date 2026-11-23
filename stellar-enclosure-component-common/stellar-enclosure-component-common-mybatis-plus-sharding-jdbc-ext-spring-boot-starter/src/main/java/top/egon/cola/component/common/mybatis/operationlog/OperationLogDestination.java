package top.egon.cola.component.common.mybatis.operationlog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Writes to the selected destination using the active source transaction. */
public interface OperationLogDestination {

    OperationLogWriteModeEnum mode();

    void append(@NotNull @Valid OperationLogEvent event);
}
