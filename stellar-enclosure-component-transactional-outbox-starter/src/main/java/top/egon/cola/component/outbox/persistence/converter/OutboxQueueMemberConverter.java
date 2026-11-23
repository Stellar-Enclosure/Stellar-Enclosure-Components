package top.egon.cola.component.outbox.persistence.converter;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.outbox.persistence.po.OutboxQueueMemberPO;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationBO.Member;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OutboxQueueMemberConverter extends BaseForwardConverter<OutboxQueueMemberPO, Member> {

    @Override
    Member toTarget(OutboxQueueMemberPO source);
}
