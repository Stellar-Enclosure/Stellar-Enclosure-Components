package top.egon.cola.component.common.mybatis.operationlog;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.validation.annotation.Validated;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Validated
public class EgonOperationLogProperties {

    public static final String PREFIX = "egon.cola.component.mybatis-plus.operation-log";

    private boolean enabled;
    @NotNull
    @Builder.Default
    private OperationLogWriteModeEnum writeMode = OperationLogWriteModeEnum.SOURCE_LOCAL;
    @Size(max = 128)
    private String sourceService;
}
