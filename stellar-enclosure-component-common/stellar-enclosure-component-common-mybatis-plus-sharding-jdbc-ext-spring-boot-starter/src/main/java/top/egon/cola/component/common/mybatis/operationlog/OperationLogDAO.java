package top.egon.cola.component.common.mybatis.operationlog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.annotations.Param;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Insert;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Query;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OperationLogDAO extends EgonColaMapper<OperationLogPO> {

    @Validated(Insert.class)
    int append(@Param("et") @Valid @NotNull(groups = Insert.class) OperationLogPO row);

    @Validated(Query.class)
    List<OperationLogRepository.PageRow> page(@Param("query") @Valid @NotNull(groups = Query.class) OperationLogRepository.WindowQuery query);

    @Validated(Query.class)
    OperationLogPO findDetail(@Param("tenantId") @NotNull(groups = Query.class) Long tenantId,
            @Param("id") @NotNull(groups = Query.class) Long id,
            @Param("occurredAt") @NotNull(groups = Query.class) Instant occurredAt);

    @Override
    default OperationLogPO selectActiveById(Serializable id) {
        throw new UnsupportedOperationException("OPERATION_LOG_EXPLICIT_ROUTE_REQUIRED");
    }

    @Override
    default List<OperationLogPO> selectActiveByIds(Collection<? extends Serializable> ids) {
        throw new UnsupportedOperationException("OPERATION_LOG_EXPLICIT_ROUTE_REQUIRED");
    }

    @Override
    default int deleteVersionedById(OperationLogPO entity) {
        throw new UnsupportedOperationException("OPERATION_LOG_IMMUTABLE");
    }

    @Override
    default int restoreVersionedById(OperationLogPO entity) {
        throw new UnsupportedOperationException("OPERATION_LOG_IMMUTABLE");
    }
}
