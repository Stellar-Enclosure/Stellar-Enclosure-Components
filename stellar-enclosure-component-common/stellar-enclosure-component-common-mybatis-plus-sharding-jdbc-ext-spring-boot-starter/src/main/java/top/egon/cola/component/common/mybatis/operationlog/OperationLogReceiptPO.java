package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Insert;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Persisted;
import top.egon.cola.component.common.mybatis.model.EgonModel;

import java.time.Instant;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "egon_cola_operation_log_receipt")
public class OperationLogReceiptPO extends EgonModel<OperationLogReceiptPO> {

    @NotNull(groups = {Insert.class, Persisted.class})
    private Instant occurredAt;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64, groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "[0-9a-f]{64}", groups = {Insert.class, Persisted.class})
    private String payloadSha256;
}
