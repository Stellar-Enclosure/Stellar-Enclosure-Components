package top.egon.cola.component.rpc.tianshu.client;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.tianshu.autoconfigure.properties.DdcProperties;
import top.egon.cola.component.tianshu.autoconfigure.properties.DdcTenantProperties;
import top.egon.cola.component.tianshu.model.management.DdcManagementConfigQuery;
import top.egon.cola.component.tianshu.model.registry.DdcServiceKind;
import top.egon.cola.component.tianshu.model.registry.DdcServiceQuery;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcConfigRuntimeServiceGrpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcManagementServiceGrpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcServiceRegistryServiceGrpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.FindConfigRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.FindConfigResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.GetServicesRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.GetServicesResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.PullConfigRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.PullConfigResponse;

import top.egon.cola.component.rpc.consumer.direct.RpcDirectClientFactory;
import top.egon.cola.component.rpc.consumer.direct.RpcDirectClientHandle;
import top.egon.cola.component.rpc.tianshu.client.ai.DdcAiProtoConverter;
import top.egon.cola.component.rpc.tianshu.contract.DdcAiResourceRpc;
import top.egon.cola.component.rpc.tianshu.security.DdcRpcClientInterceptorFactory;
import top.egon.cola.component.tianshu.model.ai.DdcAiClientScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class DdcRpcClientFactoryTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @Test
    void createsIndependentPlaintextClientsForAllThreeDdcCapabilities()
            throws Exception {
        AtomicReference<PullConfigRequest> pullRequest = new AtomicReference<>();
        AtomicReference<GetServicesRequest> servicesRequest = new AtomicReference<>();
        AtomicReference<FindConfigRequest> findRequest = new AtomicReference<>();
        Server server = NettyServerBuilder.forPort(0)
                .addService(configService(pullRequest))
                .addService(registryService(servicesRequest))
                .addService(managementService(findRequest))
                .build()
                .start();
        try {
            DdcRpcClientFactory factory = factory(server.getPort());
            try (var config = factory.configClient();
                 var registry = factory.registryClient();
                 var management = factory.managementClient()) {
                assertThat(config.client()).isNotNull();
                config.client().pull();
                registry.client().getServiceKeys(query());
                management.client().findConfig(
                        new DdcManagementConfigQuery("biz", "test", "app"));
            }

            assertThat(pullRequest.get().getScope().getBizCode())
                    .isEqualTo("biz");
            assertThat(servicesRequest.get().getQuery().getServiceKind())
                    .isEqualTo(top.egon.cola.component.rpc.tianshu.contract.proto.v1
                            .DdcServiceKind.TIANSHU_SERVICE_KIND_RPC_PROVIDER);
            assertThat(findRequest.get().getScope().getAppCode())
                    .isEqualTo("app");
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesToCreateRegistryClientWithoutPositiveTenant() {
        DdcRpcProperties rpc = new DdcRpcProperties();
        rpc.setTarget("localhost:19080");
        rpc.getAuth().setEnabled(false);
        DdcProperties ddc = new DdcProperties();
        DdcTenantProperties tenant = new DdcTenantProperties();
        DdcRpcClientFactory factory = new DdcRpcClientFactory(
                rpc,
                ddc,
                tenant,
                new RpcProcessIdentity("test", "test", "127.0.0.1", 1L, "instance-1"));

        assertThatThrownBy(factory::registryClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-id");
    }

    @Test
    void aiClientScopeAndAuthenticatedTransportShareOnePinnedTenantAndDoNotFollowPropertyMutation() {
        var rpc = new DdcRpcProperties();
        rpc.setTarget("localhost:19080");
        rpc.getAuth().getRuntime().setAccessKey("fixture-runtime");
        rpc.getAuth().getRuntime().setSecretKey("fixture-only");
        var properties = new DdcProperties();
        properties.setBizCode("biz"); properties.setEnv("test"); properties.setAppCode("app");
        var tenant = new DdcTenantProperties(); tenant.setTenantId(101L);
        var direct = mock(RpcDirectClientFactory.class);
        @SuppressWarnings("unchecked") RpcDirectClientHandle<DdcAiResourceRpc> handle = mock(RpcDirectClientHandle.class);
        var api = mock(DdcAiResourceRpc.class);
        when(handle.client()).thenReturn(api);
        when(api.getPrompt(any())).thenReturn(top.egon.cola.component.rpc.tianshu.contract.proto.v1.AiPromptResult.newBuilder()
                .setResourceId("10").setResourceKey("review").setVersionId("11").setVersionNo(2)
                .setContentSha256("a".repeat(64)).setTemplate("Review").build());
        when(direct.create(eq(DdcAiResourceRpc.class), any(), anyList())).thenReturn(handle);
        var factory = new DdcRpcClientFactory(rpc, properties, tenant,
                new RpcProcessIdentity("test", "test", "127.0.0.1", 1, "instance-1"), direct, Mappers.getMapper(DdcAiProtoConverter.class));
        var authenticatedTenants = new ArrayList<Long>();
        try (var interceptors = mockConstruction(DdcRpcClientInterceptorFactory.class,
                (interceptor, context) -> authenticatedTenants.add((Long) context.arguments().get(1)));
             var prompt = factory.promptClient(); var skill = factory.skillClient()) {
            tenant.setTenantId(202L); properties.setBizCode("other"); properties.setEnv("prod"); properties.setAppCode("another");
            var expected = new DdcAiClientScope(101, "biz", "test", "app");
            assertThat(prompt.client().scope()).isEqualTo(expected);
            assertThat(skill.client().scope()).isEqualTo(expected);
            assertThat(authenticatedTenants).containsExactly(101L, 101L);
            prompt.client().get(new top.egon.cola.component.tianshu.model.ai.DdcAiResourceQuery("prompts", "review", 2L, 10L));
            var sent = org.mockito.ArgumentCaptor.forClass(top.egon.cola.component.rpc.tianshu.contract.proto.v1.AiResourceQuery.class);
            org.mockito.Mockito.verify(api).getPrompt(sent.capture());
            assertThat(sent.getValue().getBizCode()).isEqualTo("biz");
            assertThat(sent.getValue().getEnv()).isEqualTo("test");
            assertThat(sent.getValue().getAppCode()).isEqualTo("app");
        }
    }

    private DdcRpcClientFactory factory(int port) {
        DdcRpcProperties rpc = new DdcRpcProperties();
        rpc.setTarget("localhost:" + port);
        rpc.getAuth().setEnabled(false);
        DdcProperties ddc = new DdcProperties();
        ddc.setBizCode("biz");
        ddc.setEnv("test");
        ddc.setAppCode("app");
        DdcTenantProperties tenant = new DdcTenantProperties();
        tenant.setTenantId(101L);
        return new DdcRpcClientFactory(
                rpc,
                ddc,
                tenant,
                new RpcProcessIdentity(
                        "test", "test", "127.0.0.1", 1, "instance-1")
        );
    }

    private DdcServiceQuery query() {
        return new DdcServiceQuery(
                "biz", "test", "app", DdcServiceKind.RPC_PROVIDER,
                "grpc", null, null, null
        );
    }

    private DdcConfigRuntimeServiceGrpc.DdcConfigRuntimeServiceImplBase
            configService(AtomicReference<PullConfigRequest> observed) {
        return new DdcConfigRuntimeServiceGrpc
                .DdcConfigRuntimeServiceImplBase() {
            @Override
            public void pullConfig(
                    PullConfigRequest request,
                    StreamObserver<PullConfigResponse> observer) {
                observed.set(request);
                observer.onNext(PullConfigResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
    }

    private DdcServiceRegistryServiceGrpc.DdcServiceRegistryServiceImplBase
            registryService(AtomicReference<GetServicesRequest> observed) {
        return new DdcServiceRegistryServiceGrpc
                .DdcServiceRegistryServiceImplBase() {
            @Override
            public void getServices(
                    GetServicesRequest request,
                    StreamObserver<GetServicesResponse> observer) {
                observed.set(request);
                observer.onNext(GetServicesResponse.newBuilder()
                        .setQuery(request.getQuery())
                        .build());
                observer.onCompleted();
            }
        };
    }

    private DdcManagementServiceGrpc.DdcManagementServiceImplBase
            managementService(AtomicReference<FindConfigRequest> observed) {
        return new DdcManagementServiceGrpc.DdcManagementServiceImplBase() {
            @Override
            public void findConfig(
                    FindConfigRequest request,
                    StreamObserver<FindConfigResponse> observer) {
                observed.set(request);
                observer.onNext(FindConfigResponse.newBuilder()
                        .setFound(false)
                        .build());
                observer.onCompleted();
            }
        };
    }
}
