package top.egon.cola.component.rpc.provider.server;

import com.google.protobuf.Message;
import io.grpc.Context;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.common.exception.EgonRpcRejectedException;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class RpcServerServiceDefinitionFactory {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RpcServerServiceDefinitionFactory.class);

    private final RpcProviderAvailabilityRegistry availability;

    private final List<RpcProviderExceptionMapper> exceptionMappers;

    private final RpcProviderAdmissionComponent admission;

    public RpcServerServiceDefinitionFactory(
            RpcProviderAvailabilityRegistry availability) {
        this(availability, List.of());
    }

    public RpcServerServiceDefinitionFactory(
            RpcProviderAvailabilityRegistry availability,
            List<RpcProviderExceptionMapper> exceptionMappers) {
        this(availability, exceptionMappers, null);
    }

    public RpcServerServiceDefinitionFactory(
            RpcProviderAvailabilityRegistry availability,
            List<RpcProviderExceptionMapper> exceptionMappers,
            RpcProviderAdmissionComponent admission) {
        this.availability = Objects.requireNonNull(availability, "availability");
        this.exceptionMappers = List.copyOf(exceptionMappers);
        this.admission = admission;
    }

    public List<ServerServiceDefinition> create(
            RpcProviderMethodRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (admission != null) {
            admission.validateMethods(registry.providers().stream()
                    .flatMap(provider -> registry.methods(provider.serviceIdentity()).stream()).toList());
        }
        return registry.providers().stream()
                .map(provider -> createService(registry, provider))
                .toList();
    }

    private ServerServiceDefinition createService(
            RpcProviderMethodRegistry registry,
            RpcProviderBinding provider) {
        RpcServiceIdentity service = provider.serviceIdentity();
        ServerServiceDefinition.Builder builder =
                ServerServiceDefinition.builder(service.serviceName());
        for (RpcProviderMethodBinding binding : registry.methods(service)) {
            ServerCalls.UnaryMethod<Message, Message> handler =
                    (request, observer) -> invoke(binding, request, observer);
            builder.addMethod(ServerMethodDefinition.create(
                    binding.method().grpcMethod(),
                    ServerCalls.asyncUnaryCall(handler)
            ));
        }
        return builder.build();
    }

    private void invoke(RpcProviderMethodBinding binding,
                        Message request,
                        StreamObserver<Message> observer) {
        RpcServiceIdentity service = binding.provider().serviceIdentity();
        if (!availability.isAvailable(service)) {
            observer.onError(Status.UNAVAILABLE
                    .withDescription("RPC provider is unavailable")
                    .asRuntimeException());
            return;
        }
        if (request == null) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("RPC request is required")
                    .asRuntimeException());
            return;
        }
        RpcProviderAdmissionComponent.Permit permit = null;
        if (admission != null) {
            try {
                permit = Objects.requireNonNull(admission.acquire(binding.method().fullMethodName(), request),
                        "RPC admission returned no permit");
            } catch (StatusRuntimeException trustedRejection) {
                // Only the trusted admission boundary may supply a ready-to-send authority/runtime status.
                observer.onError(trustedRejection);
                return;
            } catch (RuntimeException unexpectedFailure) {
                fail(binding, observer, unexpectedFailure);
                return;
            }
        }
        Object response;
        try {
            // Business execution stays on the gRPC calling thread, inside its original request context.
            response = binding.invoke(request);
        } catch (Throwable throwable) {
            if (permit != null) { permit.close(); }
            fail(binding, observer, throwable);
            return;
        }
        if (binding.method().invocationMode()
                == top.egon.cola.component.rpc.consumer.invocation.RpcInvocationMode.BLOCKING) {
            if (permit != null) { permit.close(); }
            if (Context.current().isCancelled()) { return; }
            try { complete(observer, response); }
            catch (Throwable throwable) { fail(binding, observer, throwable); }
            return;
        }
        if (!(response instanceof CompletionStage<?> stage)) {
            if (permit != null) {
                LOGGER.warn("RPC async provider returned no observable source; retaining admission permit method={}",
                        binding.method().fullMethodName());
            }
            fail(binding, observer, new IllegalStateException("RPC async provider returned no CompletionStage response"));
            return;
        }
        bridgeAsync(binding, observer, stage, response, permit);
    }

    private void bridgeAsync(RpcProviderMethodBinding binding, StreamObserver<Message> observer,
                             CompletionStage<?> stage, Object response, RpcProviderAdmissionComponent.Permit permit) {
        AtomicBoolean terminal = new AtomicBoolean();
        Context invocationContext = Context.current();
        TraceContext trace = TraceContext.currentOrCreate();
        Context.CancellationListener legacyCancellation = context -> cancelIfSupported(context, response);
        if (permit == null) {
            invocationContext.addListener(legacyCancellation, Runnable::run);
        }
        try {
            stage.whenComplete((value, error) -> invocationContext.run(() -> {
                try (TraceContext.Scope ignored = trace.open()) {
                    if (!terminal.compareAndSet(false, true)) { return; }
                    invocationContext.removeListener(legacyCancellation);
                    Throwable failure = error == null ? null : unwrap(error);
                    boolean uncertainCancellation = permit != null && (failure instanceof CancellationException
                            || response instanceof Future<?> future && future.isCancelled());
                    if (uncertainCancellation) {
                        // Future.cancel does not prove that its underlying work has stopped.
                        // Retain the slot until operator recovery instead of admitting additional work.
                        LOGGER.warn("RPC async source cancellation is ambiguous; retaining admission permit method={}",
                                binding.method().fullMethodName());
                    } else if (permit != null) {
                        permit.close();
                    }
                    // Completion, rather than observer state or transport cancellation, owns permit release.
                    if (invocationContext.isCancelled()) { return; }
                    if (failure != null) { fail(binding, observer, failure); return; }
                    try { complete(observer, value); }
                    catch (Throwable throwable) { fail(binding, observer, throwable); }
                }
            }));
        } catch (Throwable registrationFailure) {
            invocationContext.removeListener(legacyCancellation);
            if (!terminal.compareAndSet(false, true)) { return; }
            if (permit != null) {
                LOGGER.warn("RPC async completion could not be observed; retaining admission permit method={}",
                        binding.method().fullMethodName());
            }
            fail(binding, observer, registrationFailure);
        }
    }

    private void complete(StreamObserver<Message> observer, Object response) {
        if (!(response instanceof Message message)) {
            throw new IllegalStateException("RPC provider returned no Protobuf response");
        }
        observer.onNext(message);
        observer.onCompleted();
    }

    private void cancelIfSupported(Context context, Object response) {
        if (context.isCancelled() && response instanceof Future<?> future) {
            future.cancel(true);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void fail(
            RpcProviderMethodBinding binding,
            StreamObserver<Message> observer,
            Throwable throwable) {
        for (RpcProviderExceptionMapper mapper : exceptionMappers) {
            try {
                var mapped = mapper.map(throwable);
                if (mapped.isPresent()) {
                    observer.onError(mapped.get());
                    return;
                }
            } catch (RuntimeException mapperFailure) {
                LOGGER.error(
                        "RPC Provider exception mapper failed mapper={} failureType={}",
                        mapper.getClass().getName(),
                        mapperFailure.getClass().getName()
                );
            }
        }
        if (throwable instanceof EgonRpcRejectedException) {
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription("RPC provider rejected the request")
                    .asRuntimeException());
            return;
        }
        LOGGER.error("RPC Provider invocation failed method={} failureType={}",
                binding.method().fullMethodName(), throwable.getClass().getName());
        observer.onError(Status.INTERNAL
                .withDescription("RPC provider invocation failed")
                .asRuntimeException());
    }
}
