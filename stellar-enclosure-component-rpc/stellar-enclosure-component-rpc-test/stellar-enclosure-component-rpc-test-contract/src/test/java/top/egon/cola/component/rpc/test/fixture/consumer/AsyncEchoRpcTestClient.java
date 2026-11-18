package top.egon.cola.component.rpc.test.fixture.consumer;

import org.springframework.stereotype.Component;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.test.contract.AsyncEchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;

import java.util.concurrent.CompletionStage;

/** Async caller for the stable Service binding used by the TCP contract. */
@Component("rpcAsyncEchoTcpTestClient")
public class AsyncEchoRpcTestClient {

    @EgonRpcReference(
            bindingId = "echo-service",
            timeoutMs = 3000,
            retries = 0)
    private AsyncEchoRpc asyncEchoRpc;

    public CompletionStage<EchoResponse> echoAsync(String message) {
        return asyncEchoRpc.echoAsync(EchoRequest.newBuilder()
                .setMessage(message)
                .build());
    }

    public AsyncEchoRpc proxy() {
        return asyncEchoRpc;
    }
}
