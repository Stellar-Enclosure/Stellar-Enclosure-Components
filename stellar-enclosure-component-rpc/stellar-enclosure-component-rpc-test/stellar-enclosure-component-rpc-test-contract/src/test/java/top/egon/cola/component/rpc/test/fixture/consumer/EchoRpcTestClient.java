package top.egon.cola.component.rpc.test.fixture.consumer;

import org.springframework.stereotype.Component;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.test.contract.EchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;

@Component("rpcEchoTcpTestClient")
public class EchoRpcTestClient {

    @EgonRpcReference(bindingId = "echo-service", timeoutMs = 3000, retries = 0)
    private EchoRpc echoRpc;

    public EchoResponse echo(String message) {
        return echoRpc.echo(EchoRequest.newBuilder()
                .setMessage(message)
                .build());
    }
}
