package top.egon.cola.component.outbox.persistence.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.core.pojo.BasePojo;
import top.egon.cola.component.common.mybatis.model.EgonModel;

import java.time.Instant;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
@TableName(value = "egon_cola_outbox_queue_member", schema = "egon_outbox", autoResultMap = true)
public class OutboxQueueMemberPO extends EgonModel<OutboxQueueMemberPO> implements BasePojo {

    @PositiveOrZero
    private int shardSlot;
    @NotBlank @Size(max = 128)
    private String instanceId;
    @NotNull
    private String capabilitiesJson;
    @NotNull
    private Instant lastSeenAt;
    @TableField(exist = false)
    private Instant databaseTime;
    @TableField(exist = false)
    private Set<String> channels;
    @NotNull
    private Instant expiresAt;
}
