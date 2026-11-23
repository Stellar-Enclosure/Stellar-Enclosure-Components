package top.egon.cola.component.outbox.persistence.converter;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.outbox.persistence.po.OutboxQueueLeasePO;
import top.egon.cola.component.outbox.store.OutboxQueueKey;
import top.egon.cola.component.outbox.store.OutboxQueueLease;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, imports = OutboxQueueKey.class)
public interface OutboxQueueLeaseConverter extends BaseForwardConverter<OutboxQueueLeasePO, OutboxQueueLease> {

    @Override
    @Mapping(target = "key", expression = "java(new OutboxQueueKey(source.getShardSlot(), source.getChannel()))")
    @Mapping(target = "instanceId", source = "ownerInstanceId")
    OutboxQueueLease toTarget(OutboxQueueLeasePO source);
}
