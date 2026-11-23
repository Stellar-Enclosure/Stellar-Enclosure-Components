package top.egon.cola.component.outbox.persistence.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.outbox.persistence.po.OutboxQueueLeasePO;

import java.util.List;

@Mapper
@Validated
@EgonColaExplicitTenantScopeMapper(technicalModel = OutboxQueueLeasePO.class,
        technicalTable = "egon_outbox.egon_cola_outbox_queue_lease")
public interface OutboxQueueLeaseDAO {

    OutboxQueueLeasePO lockQueue(@Param("slot") int slot, @Param("channel") String channel);

    Boolean current(@Param("slot") int slot, @Param("channel") String channel,
                    @Param("instance") String instance, @Param("epoch") long epoch);

    List<OutboxQueueLeasePO> active(@Param("slot") int slot, @Param("limit") int limit);

    int acquireExpired(@Param("slot") int slot, @Param("channel") String channel,
                       @Param("instance") String instance, @Param("leaseMillis") long leaseMillis);

    int register(@Param("slot") int slot, @Param("id") long id, @Param("channel") String channel,
                 @Param("instance") String instance, @Param("leaseMillis") long leaseMillis);

    int renew(@Param("slot") int slot, @Param("channel") String channel, @Param("instance") String instance,
              @Param("epoch") long epoch, @Param("leaseMillis") long leaseMillis);

    int release(@Param("slot") int slot, @Param("channel") String channel,
                @Param("instance") String instance, @Param("epoch") long epoch);
}
