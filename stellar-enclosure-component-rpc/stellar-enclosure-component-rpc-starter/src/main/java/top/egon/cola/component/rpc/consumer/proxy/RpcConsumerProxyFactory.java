package top.egon.cola.component.rpc.consumer.proxy;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Message;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.stub.ClientCalls;
import org.springframework.cglib.core.ReflectUtils;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInterceptorFactory;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.interceptor.RpcConsumerClientInterceptor;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationMode;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationPlan;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinition;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.descriptor.RpcMethodDescriptor;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Builds a typed proxy whose method plans use one trusted Kubernetes Service binding. */
public final class RpcConsumerProxyFactory {

    private final RpcInvocationExecutor executor;
    private final RpcProcessIdentity processIdentity;
    private final List<RpcClientInterceptorFactory> interceptorFactories;

    public RpcConsumerProxyFactory(RpcInvocationExecutor executor, RpcProcessIdentity processIdentity,
                                    List<RpcClientInterceptorFactory> interceptorFactories) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.processIdentity = Objects.requireNonNull(processIdentity, "processIdentity");
        this.interceptorFactories = interceptorFactories == null ? List.of() : List.copyOf(interceptorFactories);
    }

    public <T> T create(RpcContractDescriptor contract, RpcReferenceDefinition definition,
                        RpcReferenceStrategy strategy) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(strategy, "strategy");
        if (!definition.target().equals(strategy.target())) {
            throw new IllegalArgumentException("RPC strategy does not match the trusted binding");
        }
        Map<Method, RpcInvocationPlan> plans = new java.util.HashMap<>();
        Map<Method, RpcInvocationMode> modes = new java.util.HashMap<>();
        for (RpcMethodDescriptor method : contract.methods()) {
            plans.put(method.javaMethod(), plan(contract, definition, strategy, method,
                    definition.policyFor(method.javaMethod())));
            modes.put(method.javaMethod(), method.invocationMode());
        }
        return cglib(contract, new RpcConsumerMethodInterceptor(contract, plans, modes, executor));
    }

    private RpcInvocationPlan plan(RpcContractDescriptor contract, RpcReferenceDefinition definition,
                                    RpcReferenceStrategy strategy, RpcMethodDescriptor method,
                                    RpcReferencePolicy policy) {
        return new RpcInvocationPlan(contract.serviceName(), method.fullMethodName(), policy,
                strategy, method.responseType(), typedInvoker(contract, definition, method));
    }

    private RpcInvocationPlan.InvocationFactory typedInvoker(RpcContractDescriptor contract,
                                                              RpcReferenceDefinition definition,
                                                              RpcMethodDescriptor method) {
        return (request, logicalCallId) -> {
            Message message = (Message) request;
            RpcClientInvocation context = RpcClientInvocation.typed(contract, method, message, processIdentity,
                    definition.serviceIdentity().group(), definition.serviceIdentity().version(),
                    logicalCallId, definition.target());
            List<ClientInterceptor> extensions = interceptorFactories.stream().map(factory -> factory.create(context)).toList();
            String requestDigest = RpcInvocationMetadata.canonicalRequestDigest(message);
            return (ignored, lease, timeout, attempt) -> {
                List<ClientInterceptor> interceptors = new ArrayList<>(extensions);
                interceptors.add(RpcConsumerClientInterceptor.forTarget(contract.serviceName(),
                        definition.serviceIdentity().group(), definition.serviceIdentity().version(),
                        processIdentity, logicalCallId, attempt.number(), requestDigest, attempt::markResponseCommitted));
                Channel channel = ClientInterceptors.interceptForward(lease.channel(), interceptors);
                CallOptions options = CallOptions.DEFAULT.withDeadline(attempt.deadline());
                ListenableFuture<Message> future = ClientCalls.futureUnaryCall(
                        channel.newCall(method.grpcMethod(), options), message);
                return new RpcInvocationPlan.Attempt(futureCompletion(future), () -> future.cancel(true));
            };
        };
    }

    private static <T> CompletableFuture<Object> futureCompletion(ListenableFuture<T> future) {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        future.addListener(() -> {
            try { completion.complete(future.get()); }
            catch (CancellationException exception) { completion.cancel(false); }
            catch (ExecutionException exception) { completion.completeExceptionally(exception.getCause()); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completion.completeExceptionally(exception);
            }
        }, Runnable::run);
        return completion;
    }

    private <T> T cglib(
            RpcContractDescriptor contract,
            RpcConsumerMethodInterceptor callback) {
        Enhancer enhancer = new Enhancer();
        ClassLoader contractLoader = contract.contractType().getClassLoader();
        boolean accessibleContract = isPublicType(contract.contractType());
        enhancer.setClassLoader(accessibleContract
                ? new CglibClassLoader(contractLoader)
                : contractLoader);
        enhancer.setSuperclass(Object.class);
        enhancer.setInterfaces(new Class<?>[]{contract.contractType()});
        enhancer.setCallback(callback);
        enhancer.setUseFactory(!accessibleContract);
        Object proxy = accessibleContract
                ? enhancer.create()
                : createPackagePrivateCglib(enhancer, contract, callback);
        @SuppressWarnings("unchecked")
        T typed = (T) proxy;
        return typed;
    }

    private Object createPackagePrivateCglib(
            Enhancer enhancer,
            RpcContractDescriptor contract,
            RpcConsumerMethodInterceptor callback) {
        java.util.concurrent.atomic.AtomicReference<byte[]> generated =
                new java.util.concurrent.atomic.AtomicReference<>();
        synchronized (RpcConsumerProxyFactory.class) {
            ReflectUtils.setGeneratedClassHandler(
                    (ignored, bytes) -> generated.set(bytes)
            );
            try {
                try {
                    return enhancer.create();
                } catch (RuntimeException generationFailure) {
                    byte[] bytes = generated.get();
                    if (bytes == null) {
                        return compatibilityProxy(contract, callback);
                    }
                    try {
                        Class<?> generatedType = MethodHandles.privateLookupIn(
                                        contract.contractType(),
                                        MethodHandles.lookup()
                                )
                                .defineClass(bytes);
                        Object proxy = generatedType.getDeclaredConstructor()
                                .newInstance();
                        ((Factory) proxy).setCallbacks(
                                new org.springframework.cglib.proxy.Callback[]{callback}
                        );
                        return proxy;
                    } catch (ReflectiveOperationException | LinkageError ignored) {
                        return compatibilityProxy(contract, callback);
                    }
                }
            } finally {
                ReflectUtils.setGeneratedClassHandler(null);
            }
        }
    }

    /**
     * Package-private test fixtures cannot be defined into the application
     * class loader on Java's module path. Keep the compatibility surface
     * callable while public application contracts always use CGLIB above.
     */
    private Object compatibilityProxy(
            RpcContractDescriptor contract,
            RpcConsumerMethodInterceptor callback) {
        try {
            Class<?> proxyType = Proxy.getProxyClass(
                    contract.contractType().getClassLoader(),
                    contract.contractType()
            );
            java.lang.reflect.Constructor<?> constructor =
                    proxyType.getConstructor(java.lang.reflect.InvocationHandler.class);
            return constructor.newInstance(
                    (java.lang.reflect.InvocationHandler) (proxy, method, args) ->
                            callback.intercept(proxy, method, args, null)
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "failed to initialize RPC compatibility proxy",
                    exception
            );
        }
    }

    private boolean isPublicType(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
            current = current.getEnclosingClass();
        }
        return true;
    }

    /** ClassLoader hook used by Spring's repackaged CGLIB on Java modules. */
    public static final class CglibClassLoader extends ClassLoader {

        public CglibClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> publicDefineClass(
                String name,
                byte[] bytes,
                java.security.ProtectionDomain protectionDomain) {
            return defineClass(name, bytes, 0, bytes.length, protectionDomain);
        }
    }

}
