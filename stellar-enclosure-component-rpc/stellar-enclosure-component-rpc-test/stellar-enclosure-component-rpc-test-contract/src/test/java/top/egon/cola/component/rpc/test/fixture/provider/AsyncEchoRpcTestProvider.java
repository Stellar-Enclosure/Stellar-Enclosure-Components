package top.egon.cola.component.rpc.test.fixture.provider;

import io.grpc.Context;
import top.egon.cola.component.rpc.annotation.EgonRpcProvider;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.test.contract.AsyncEchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Controllable server work with independent observation of transport cancellation. */
@EgonRpcProvider
public class AsyncEchoRpcTestProvider implements AsyncEchoRpc {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger transportCancellations = new AtomicInteger();

    private volatile PendingEchoVO active;

    @Override
    public CompletionStage<EchoResponse> echoAsync(EchoRequest request) {
        RpcInvocationMetadata invocation = RpcInvocationMetadata.current();
        String invocationId = invocation == null || invocation.invocationId() == null
                ? ""
                : invocation.invocationId();
        EchoResponse response = EchoResponse.newBuilder()
                .setProviderId("async-provider")
                .setMessage(request.getMessage())
                .setInvocationId(invocationId)
                .setTraceId(invocation == null || invocation.traceId() == null
                        ? ""
                        : invocation.traceId())
                .build();
        CompletableFuture<EchoResponse> result = new CompletableFuture<>();
        active = new PendingEchoVO(result, response);
        Context.current().addListener(ignored -> transportCancellations.incrementAndGet(), Runnable::run);
        calls.incrementAndGet();
        return result;
    }

    public void complete() {
        PendingEchoVO pending = active;
        if (pending == null) {
            throw new IllegalStateException("no async RPC is pending");
        }
        pending.result().complete(pending.response());
    }

    public boolean cancelPending() {
        PendingEchoVO pending = active;
        return pending != null && pending.result().cancel(true);
    }

    public int calls() {
        return calls.get();
    }

    public boolean pendingCancelled() {
        PendingEchoVO pending = active;
        return pending != null && pending.result().isCancelled();
    }

    public int transportCancellations() {
        return transportCancellations.get();
    }

    private record PendingEchoVO(CompletableFuture<EchoResponse> result, EchoResponse response) {}
}
