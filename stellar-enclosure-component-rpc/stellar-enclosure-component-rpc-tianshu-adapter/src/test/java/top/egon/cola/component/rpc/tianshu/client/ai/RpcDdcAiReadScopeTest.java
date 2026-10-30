package top.egon.cola.component.rpc.tianshu.client.ai;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import top.egon.cola.component.rpc.tianshu.contract.DdcAiResourceRpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.AiPromptResult;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.AiResourceQuery;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.AiSkillResult;
import top.egon.cola.component.rpc.tianshu.mapping.DdcRpcStatusExceptionMapper;
import top.egon.cola.component.tianshu.autoconfigure.properties.DdcProperties;
import top.egon.cola.component.tianshu.model.ai.DdcAiClientScope;
import top.egon.cola.component.tianshu.model.ai.DdcAiResourceQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcDdcAiReadScopeTest {
    private final DdcAiResourceRpc rpc = mock(DdcAiResourceRpc.class);
    private final DdcAiProtoConverter converter = Mappers.getMapper(DdcAiProtoConverter.class);
    private final DdcRpcStatusExceptionMapper errors = new DdcRpcStatusExceptionMapper();
    private final DdcAiResourceQuery query = new DdcAiResourceQuery("prompts", "review", 2L, 10L);

    @Test
    void scopedPromptAndSkillQueriesUseExactlyTheReturnedImmutableScope() {
        var scope = new DdcAiClientScope(20, "xingyuan", "test", "tianji");
        var prompt = RpcDdcPromptClient.scoped(rpc, converter, errors, scope);
        var skill = RpcDdcSkillClient.scoped(rpc, converter, errors, scope);
        when(rpc.getPrompt(any())).thenReturn(AiPromptResult.newBuilder().setResourceId("10").setResourceKey("review")
                .setVersionId("11").setVersionNo(2).setContentSha256("a".repeat(64)).setTemplate("Review").build());
        when(rpc.getSkill(any())).thenReturn(AiSkillResult.newBuilder().setResourceId("10").setResourceKey("review")
                .setVersionId("11").setVersionNo(2).setContentSha256("a".repeat(64)).build());
        assertThat(prompt.get(query).getTemplate()).isEqualTo("Review");
        assertThat(skill.get(query).getVersionNo()).isEqualTo(2L);
        assertThat(prompt.scope()).isEqualTo(scope);
        assertThat(skill.scope()).isEqualTo(scope);
        var sent = ArgumentCaptor.forClass(AiResourceQuery.class);
        verify(rpc).getPrompt(sent.capture());
        verify(rpc).getSkill(sent.capture());
        for (var request : sent.getAllValues()) {
            assertThat(request.getBizCode()).isEqualTo(scope.bizCode());
            assertThat(request.getEnv()).isEqualTo(scope.env());
            assertThat(request.getAppCode()).isEqualTo(scope.appCode());
            assertThat(request.getNamespaceCode()).isEqualTo("prompts");
            assertThat(request.getResourceId()).isEqualTo("10");
            assertThat(request.getVersionNo()).isEqualTo(2L);
        }
    }

    @Test
    void oldConstructorsKeepReadingButDoNotInventVerifiedScope() {
        var properties = new DdcProperties();
        properties.setBizCode("biz"); properties.setEnv("test"); properties.setAppCode("app");
        var prompt = new RpcDdcPromptClient(rpc, converter, errors, properties);
        var skill = new RpcDdcSkillClient(rpc, converter, errors, properties);
        when(rpc.getPrompt(any())).thenReturn(AiPromptResult.newBuilder().setResourceId("10").setResourceKey("review")
                .setVersionId("11").setVersionNo(2).setContentSha256("a".repeat(64)).setTemplate("Review").build());
        properties.setBizCode("updated-biz");
        assertThat(prompt.get(query).getTemplate()).isEqualTo("Review");
        var sent = ArgumentCaptor.forClass(AiResourceQuery.class);
        verify(rpc).getPrompt(sent.capture());
        assertThat(sent.getValue().getBizCode()).isEqualTo("updated-biz");
        assertThatThrownBy(prompt::scope).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(skill::scope).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RpcDdcPromptClient.scoped(rpc, converter, errors, null)).isInstanceOf(NullPointerException.class);
    }
}
