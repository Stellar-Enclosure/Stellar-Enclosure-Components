package top.egon.cola.component.rpc.consumer.reference;

import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;

/** A single trusted Service target with pool-owned channel and reference lifecycle. */
public interface RpcReferenceStrategy extends AutoCloseable {
    RpcServiceTargetVO target();
    String queryIdentity();
    long revision();
    RpcChannelLease acquire();
    @Override void close();
}
