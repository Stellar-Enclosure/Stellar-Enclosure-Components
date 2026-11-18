package top.egon.cola.component.rpc.provider.server;

import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.rpc.config.RpcTransportSecurity;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;

/** Creates the internal native gRPC listener with cleartext HTTP/2 transport. */
@Slf4j
public class RpcProviderServerFactory {

    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 4 * 1024 * 1024;

    private final int maxInboundMessageSize;

    public RpcProviderServerFactory() {
        this(DEFAULT_MAX_INBOUND_MESSAGE_SIZE);
    }

    public RpcProviderServerFactory(
            int maxInboundMessageSize) {
        if (maxInboundMessageSize < 1024) {
            throw new IllegalArgumentException(
                    "maxInboundMessageSize must be at least 1024"
            );
        }
        this.maxInboundMessageSize = maxInboundMessageSize;
    }

    public Server create(String bindAddress,
                         int port,
                         Collection<ServerServiceDefinition> services,
                         List<ServerInterceptor> interceptors) {
        if (port == RpcTransportSecurity.GAMMA_FALLBACK_PORT) {
            throw new IllegalArgumentException("RPC may not listen on the reserved GAMMA fallback port");
        }
        NettyServerBuilder builder = NettyServerBuilder.forAddress(
                new InetSocketAddress(bindAddress, port)
        ).maxInboundMessageSize(maxInboundMessageSize);
        services.stream()
                .map(service -> ServerInterceptors.interceptForward(
                        service,
                        interceptors
                ))
                .forEach(builder::addService);
        log.debug("Creating internal h2c RPC listener bindAddress={} port={} services={}",
                bindAddress, port, services.size());
        return builder.build();
    }
}
