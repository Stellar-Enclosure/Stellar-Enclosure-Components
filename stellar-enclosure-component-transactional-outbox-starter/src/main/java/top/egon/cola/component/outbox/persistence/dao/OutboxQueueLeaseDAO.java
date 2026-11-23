package top.egon.cola.component.outbox.persistence.dao;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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

    OutboxQueueLeasePO lockQueue(@Param("slot") @PositiveOrZero int slot, @Param("channel") @NotBlank @Size(max = 64) String channel);

    Boolean current(@Param("slot") @PositiveOrZero int slot, @Param("channel") @NotBlank @Size(max = 64) String channel,
                    @Param("instance") @NotBlank @Size(max = 128) String instance, @Param("epoch") @Positive long epoch);

    List<OutboxQueueLeasePO> active(@Param("slot") @PositiveOrZero int slot, @Param("limit") @Positive @Max(4097) int limit);

    int acquireExpired(@Param("slot") @PositiveOrZero int slot, @Param("channel") @NotBlank @Size(max = 64) String channel,
                       @Param("instance") @NotBlank @Size(max = 128) String instance, @Param("leaseMillis") @Positive long leaseMillis);

    int register(@Param("slot") @PositiveOrZero int slot, @Param("id") @Positive long id, @Param("channel") @NotBlank @Size(max = 64) String channel,
                 @Param("instance") @NotBlank @Size(max = 128) String instance, @Param("leaseMillis") @Positive long leaseMillis);

    int renew(@Param("slot") @PositiveOrZero int slot, @Param("channel") @NotBlank @Size(max = 64) String channel, @Param("instance") @NotBlank @Size(max = 128) String instance,
              @Param("epoch") @Positive long epoch, @Param("leaseMillis") @Positive long leaseMillis);

    int release(@Param("slot") @PositiveOrZero int slot, @Param("channel") @NotBlank @Size(max = 64) String channel,
                @Param("instance") @NotBlank @Size(max = 128) String instance, @Param("epoch") @Positive long epoch);
}
