package top.egon.cola.component.rpc.consumer.channel;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;

/** Builds h2c channels only for validated stable Service bindings. */
@Slf4j
public class RpcConsumerChannelFactory {

    private final int maxInboundMessageSize;
    private final int connectTimeoutMs;

    public RpcConsumerChannelFactory(int maxInboundMessageSize, int connectTimeoutMs) {
        if (maxInboundMessageSize < 1024 || connectTimeoutMs < 1) {
            throw new IllegalArgumentException("RPC message size and TCP connect timeout are invalid");
        }
        this.maxInboundMessageSize = maxInboundMessageSize;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public ManagedChannel create(RpcChannelKey key) {
        log.debug("Opening an internal RPC h2c channel");
        return NettyChannelBuilder.forTarget(key.dnsTarget())
                .usePlaintext()
                .disableServiceConfigLookUp()
                .disableRetry()
                .maxInboundMessageSize(maxInboundMessageSize)
                .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .build();
    }
}
