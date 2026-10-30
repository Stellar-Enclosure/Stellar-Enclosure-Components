package top.egon.cola.component.rpc.tianshu.client.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.tianshu.contract.DdcAiResourceRpc;
import top.egon.cola.component.rpc.tianshu.mapping.DdcRpcStatusExceptionMapper;
import top.egon.cola.component.rpc.tianshu.security.DdcRpcOperation;
import top.egon.cola.component.tianshu.api.client.DdcSkillClient;
import top.egon.cola.component.tianshu.autoconfigure.properties.DdcProperties;
import top.egon.cola.component.tianshu.model.ai.DdcAiResourceQuery;
import top.egon.cola.component.tianshu.model.ai.DdcAiClientScope;
import top.egon.cola.component.tianshu.model.ai.DdcSkillResult;

/** Direct RPC adapter for the read-only published Skill Port. */
@Validated
public final class RpcDdcSkillClient implements DdcSkillClient {

    private final DdcAiResourceRpc rpc;
    private final DdcAiProtoConverter converter;
    private final DdcRpcStatusExceptionMapper errors;
    private final DdcProperties properties;
    private final DdcAiClientScope scope;

    public RpcDdcSkillClient(
            DdcAiResourceRpc rpc,
            DdcAiProtoConverter converter,
            DdcRpcStatusExceptionMapper errors,
            DdcProperties properties) {
        this(rpc, converter, errors, properties, null);
    }

    /** Creates a client with the exact immutable scope used by its transport. */
    public static RpcDdcSkillClient scoped(DdcAiResourceRpc rpc, DdcAiProtoConverter converter,
            DdcRpcStatusExceptionMapper errors, DdcAiClientScope scope) {
        return new RpcDdcSkillClient(rpc, converter, errors, null, java.util.Objects.requireNonNull(scope, "scope"));
    }

    private RpcDdcSkillClient(DdcAiResourceRpc rpc, DdcAiProtoConverter converter,
            DdcRpcStatusExceptionMapper errors, DdcProperties properties, DdcAiClientScope scope) {
        this.rpc = rpc;
        this.converter = converter;
        this.errors = errors;
        this.properties = properties;
        this.scope = scope;
    }

    @Override
    public DdcAiClientScope scope() {
        if (scope == null) throw new IllegalStateException("AI client scope is unavailable");
        return scope;
    }

    @Override
    public DdcSkillResult get(@NotNull @Valid DdcAiResourceQuery query) {
        var request = converter.toQuery(new DdcAiQueryDTO(
                scope == null ? properties.getBizCode() : scope.bizCode(), query.namespaceCode(),
                scope == null ? properties.getEnv() : scope.env(), scope == null ? properties.getAppCode() : scope.appCode(),
                query.resourceKey(), query.versionNo(), query.resourceId()));
        var response = invoke(() -> rpc.getSkill(request));
        return converter.fromSkillResult(response);
    }

    private <T> T invoke(Invocation<T> invocation) {
        try {
            return invocation.get();
        } catch (RuntimeException failure) {
            throw errors.restore(failure, DdcRpcOperation.AI_READ);
        }
    }

    @FunctionalInterface
    private interface Invocation<T> {
        T get();
    }
}
