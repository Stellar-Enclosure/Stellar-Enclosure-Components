package top.egon.cola.component.rpc.provider.server;

import com.google.protobuf.Message;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Resolves an authenticated, method-authorized identity; request metadata is never an identity source. */
@FunctionalInterface
public interface RpcAdmissionIdentityResolver {

    @Valid
    RpcAdmissionIdentityVO resolve(@NotBlank @Size(max = 512) String fullMethodName,
                                   @NotNull Message request);
}
