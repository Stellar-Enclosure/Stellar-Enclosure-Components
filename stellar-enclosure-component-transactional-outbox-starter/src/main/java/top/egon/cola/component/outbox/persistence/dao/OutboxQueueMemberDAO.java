package top.egon.cola.component.outbox.persistence.dao;

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

    int refresh(@Param("slot") int slot, @Param("instance") String instance, @Param("json") String json,
                @Param("ttlMillis") long ttlMillis);

    int register(@Param("slot") int slot, @Param("id") long id, @Param("instance") String instance,
                 @Param("json") String json, @Param("ttlMillis") long ttlMillis);

    List<OutboxQueueMemberPO> active(@Param("slot") int slot, @Param("limit") int limit);

    List<Long> staleIds(@Param("slot") int slot, @Param("retentionMillis") long retentionMillis,
                        @Param("limit") int limit);

    int removeStale(@Param("slot") int slot, @Param("id") long id,
                    @Param("retentionMillis") long retentionMillis);
}
