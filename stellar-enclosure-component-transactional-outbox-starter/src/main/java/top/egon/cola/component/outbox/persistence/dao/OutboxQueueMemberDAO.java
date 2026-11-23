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
import top.egon.cola.component.outbox.persistence.po.OutboxQueueMemberPO;

import java.util.List;

@Mapper
@Validated
@EgonColaExplicitTenantScopeMapper(technicalModel = OutboxQueueMemberPO.class,
        technicalTable = "egon_outbox.egon_cola_outbox_queue_member")
public interface OutboxQueueMemberDAO {

    int refresh(@Param("slot") @PositiveOrZero int slot, @Param("instance") @NotBlank @Size(max = 128) String instance, @Param("json") @NotBlank @Size(max = 32768) String json,
                @Param("ttlMillis") @PositiveOrZero long ttlMillis);

    int register(@Param("slot") @PositiveOrZero int slot, @Param("id") @Positive long id, @Param("instance") @NotBlank @Size(max = 128) String instance,
                 @Param("json") @NotBlank @Size(max = 32768) String json, @Param("ttlMillis") @PositiveOrZero long ttlMillis);

    List<OutboxQueueMemberPO> active(@Param("slot") @PositiveOrZero int slot, @Param("limit") @Positive @Max(4097) int limit);

    List<Long> staleIds(@Param("slot") @PositiveOrZero int slot, @Param("retentionMillis") @Positive long retentionMillis,
                        @Param("limit") @Positive @Max(4097) int limit);

    int removeStale(@Param("slot") @PositiveOrZero int slot, @Param("id") @Positive long id,
                    @Param("retentionMillis") @Positive long retentionMillis);
}
