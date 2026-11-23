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

public interface OperationLogReceiptDAO extends EgonColaMapper<OperationLogReceiptPO> {

    @Validated(Insert.class)
    int insertIfAbsent(@Param("et") @Valid @NotNull(groups = Insert.class) OperationLogReceiptPO row);

    @Validated(Query.class)
    ReceiptConfirmation confirm(@Param("tenantId") @NotNull(groups = Query.class) Long tenantId,
            @Param("id") @NotNull(groups = Query.class) Long id);

    record ReceiptConfirmation(Instant occurredAt, String payloadSha256) {}

    @Override
    default OperationLogReceiptPO selectActiveById(Serializable id) {
        throw new UnsupportedOperationException("OPERATION_LOG_EXPLICIT_ROUTE_REQUIRED");
    }

    @Override
    default List<OperationLogReceiptPO> selectActiveByIds(Collection<? extends Serializable> ids) {
        throw new UnsupportedOperationException("OPERATION_LOG_EXPLICIT_ROUTE_REQUIRED");
    }

    @Override
    default int deleteVersionedById(OperationLogReceiptPO entity) {
        throw new UnsupportedOperationException("OPERATION_LOG_IMMUTABLE");
    }

    @Override
    default int restoreVersionedById(OperationLogReceiptPO entity) {
        throw new UnsupportedOperationException("OPERATION_LOG_IMMUTABLE");
    }
}
