package top.egon.cola.component.rpc.test.fixture.provider;

import org.springframework.beans.factory.annotation.Value;
import top.egon.cola.component.rpc.annotation.EgonRpcProvider;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.test.contract.EchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;

@EgonRpcProvider
public class EchoRpcTestProvider implements EchoRpc {

    private final String providerId;

    public EchoRpcTestProvider(
            @Value("${rpc.test.provider-id:provider-default}")
            String providerId) {
        this.providerId = providerId;
    }

    @Override
    public EchoResponse echo(EchoRequest request) {
        RpcInvocationMetadata invocation = RpcInvocationMetadata.current();
        String invocationId = invocation == null
                ? ""
                : value(invocation.invocationId());
        String traceId = invocation == null
                ? ""
                : value(invocation.traceId());
        return EchoResponse.newBuilder()
                .setProviderId(providerId)
                .setMessage(request.getMessage())
                .setInvocationId(invocationId)
                .setTraceId(traceId)
                .build();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
