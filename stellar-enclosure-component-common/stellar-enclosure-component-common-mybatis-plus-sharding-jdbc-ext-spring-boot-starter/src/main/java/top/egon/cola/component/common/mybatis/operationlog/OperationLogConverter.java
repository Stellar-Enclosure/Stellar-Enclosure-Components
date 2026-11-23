package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OperationLogConverter extends BaseForwardConverter<OperationLogEvent, OperationLogPO> {

    @Override
    @Mapping(source = "eventId", target = "id")
    @Mapping(target = "createUserId", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateUserId", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    OperationLogPO toTarget(OperationLogEvent source);

    default ObjectNode copyDetails(ObjectNode source) {
        return source == null ? null : source.deepCopy();
    }
}
