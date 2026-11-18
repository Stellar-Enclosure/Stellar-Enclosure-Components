package top.egon.cola.component.rpc.provider.lifecycle;

import io.grpc.Server;
import io.grpc.ServerInterceptor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.config.RpcTransportSecurity;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerFactory;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the local listener and its readiness/drain gate; discovery belongs to Kubernetes. */
@Slf4j
@RequiredArgsConstructor
public class RpcProviderLifecycle implements SmartLifecycle {

    @NonNull
    private final RpcProviderMethodRegistry registry;

    @NonNull
    private final RpcServerServiceDefinitionFactory definitionFactory;

    @NonNull
    private final RpcProviderServerFactory serverFactory;

    @NonNull
    private final RpcProviderAvailabilityRegistry availability;

    @NonNull
    private final List<ServerInterceptor> interceptors;

    @NonNull
    private final EgonRpcProperties rpcProperties;

    private final AtomicBoolean stopCallbackInvoked = new AtomicBoolean();

    private volatile RpcProviderRuntimeState state = RpcProviderRuntimeState.NEW;

    private volatile Server server;

    @Override
    public synchronized void start() {
        if (state == RpcProviderRuntimeState.READY) {
            return;
        }
        if (state != RpcProviderRuntimeState.NEW) {
            throw startFailed("RPC Provider is not restartable after shutdown/failure", null);
        }
        availability.clear();
        try {
            validateProperties();
            List<RpcProviderBinding> providers = registry.providers();
            if (providers.isEmpty()) {
                throw startFailed("no RPC Provider bean was found", null);
            }
            state = RpcProviderRuntimeState.STARTING;
            EgonRpcProperties.Provider properties = rpcProperties.getProvider();
            // Publish the prepared server before start so even a failed bind is cleaned up.
            server = serverFactory.create(
                    properties.getBindAddress(),
                    properties.getPort(),
                    definitionFactory.create(registry),
                    List.copyOf(interceptors)
            );
            server.start();
            providers.forEach(binding -> availability.available(binding.serviceIdentity()));
            state = RpcProviderRuntimeState.READY;
            log.info("Internal h2c RPC provider ready port={} services={}",
                    server.getPort(), providers.size());
        } catch (IOException | RuntimeException exception) {
            stopInfrastructure();
            state = RpcProviderRuntimeState.FAILED;
            if (exception instanceof EgonRpcException rpcException
                    && rpcException.getRpcErrorCode() == EgonRpcErrorCode.RPC_PROVIDER_START_FAILED) {
                throw rpcException;
            }
            throw startFailed("RPC Provider startup failed", exception);
        }
    }

    @Override
    public synchronized void stop() {
        stop(null);
    }

    @Override
    public synchronized void stop(Runnable callback) {
        if (state == RpcProviderRuntimeState.STOPPED) {
            runOnce(callback);
            return;
        }
        state = RpcProviderRuntimeState.DRAINING;
        availability.clear();
        Server current = server;
        server = null;
        try {
            if (current != null) {
                current.shutdown();
                try {
                    if (!current.awaitTermination(
                            rpcProperties.getProvider().getGracefulShutdownTimeoutMs(),
                            TimeUnit.MILLISECONDS
                    )) {
                        current.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    current.shutdownNow();
                }
            }
        } finally {
            state = RpcProviderRuntimeState.STOPPED;
            runOnce(callback);
            log.info("Internal h2c RPC provider stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return state == RpcProviderRuntimeState.READY;
    }

    public RpcProviderRuntimeState state() {
        return state;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public int boundPort() {
        Server current = server;
        return current == null ? -1 : current.getPort();
    }

    private void validateProperties() {
        EgonRpcProperties.Provider properties = rpcProperties.getProvider();
        if (properties.getBindAddress() == null || properties.getBindAddress().isBlank()) {
            throw startFailed("RPC Provider bind address is required", null);
        }
        if (properties.getPort() < 0 || properties.getPort() > 65535
                || properties.getPort() == RpcTransportSecurity.GAMMA_FALLBACK_PORT) {
            throw startFailed("RPC Provider port is invalid", null);
        }
        if (properties.getMaxInboundMessageSize() < 1024) {
            throw startFailed("RPC Provider max inbound message size is invalid", null);
        }
        if (properties.getGracefulShutdownTimeoutMs() < 0) {
            throw startFailed("RPC Provider shutdown timeout is invalid", null);
        }
    }

    private void stopInfrastructure() {
        availability.clear();
        Server current = server;
        server = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void runOnce(Runnable callback) {
        if (callback != null && stopCallbackInvoked.compareAndSet(false, true)) {
            callback.run();
        }
    }

    private EgonRpcException startFailed(String message, Throwable cause) {
        return cause == null
                ? new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_START_FAILED, message)
                : new EgonRpcException(EgonRpcErrorCode.RPC_PROVIDER_START_FAILED, message, cause);
    }
}
