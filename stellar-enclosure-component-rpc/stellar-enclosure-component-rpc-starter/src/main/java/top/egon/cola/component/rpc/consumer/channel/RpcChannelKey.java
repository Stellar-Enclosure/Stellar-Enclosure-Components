package top.egon.cola.component.rpc.consumer.channel;

import lombok.EqualsAndHashCode;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;

import java.util.Objects;

/** Shared transport identity for one revision of a stable Kubernetes Service binding. */
@EqualsAndHashCode
public final class RpcChannelKey {

    private final String bindingId;
    private final long bindingVersion;
    private final String dnsTarget;

    private RpcChannelKey(RpcServiceTargetVO target) {
        bindingId = target.bindingId();
        bindingVersion = target.version();
        dnsTarget = target.dnsTarget();
    }

    public static RpcChannelKey from(RpcServiceTargetVO target) {
        return new RpcChannelKey(Objects.requireNonNull(target, "target"));
    }

    public String dnsTarget() {
        return dnsTarget;
    }
}
