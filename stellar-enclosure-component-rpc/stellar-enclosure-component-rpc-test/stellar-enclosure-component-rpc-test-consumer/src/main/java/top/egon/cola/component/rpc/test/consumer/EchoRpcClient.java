package top.egon.cola.component.rpc.test.consumer;

import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.test.contract.EchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;

@Slf4j
@Validated
@Component("echoRpcClient")
public class EchoRpcClient {

    @EgonRpcReference(bindingId = "echo-service", timeoutMs = 3000, retries = 0)
    private EchoRpc echoRpc;

    public EchoResponse echo(@NotNull String message) {
        return echoRpc.echo(EchoRequest.newBuilder()
                .setMessage(message)
                .build());
    }

    EchoRpc rpcProxy() {
        return echoRpc;
    }
}
