package top.egon.cola.component.rpc.tianshu.autoconfigure;

import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;

import java.time.Duration;

/** Local reference to an approved native SI binding; it contains no addresses or credentials. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = DdcRpcProperties.PREFIX, ignoreUnknownFields = false)
public class DdcRpcProperties {
    public static final String PREFIX = "egon.cola.component.tianshu.si.rpc";
    public static final String BEAN_NAME = PREFIX + "-top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties";

    private boolean enabled;
    private String bindingId;
    private long bindingVersion;
    private Duration defaultTimeout;

    @AssertTrue
    public boolean isNativeBindingValid() {
        return !enabled || RpcServiceTargetVO.isBindingId(bindingId) && bindingVersion > 0 && defaultTimeout != null
                && !defaultTimeout.isNegative() && !defaultTimeout.isZero() && defaultTimeout.compareTo(Duration.ofSeconds(5)) <= 0
                && defaultTimeout.toMillis() > 0;
    }
}
