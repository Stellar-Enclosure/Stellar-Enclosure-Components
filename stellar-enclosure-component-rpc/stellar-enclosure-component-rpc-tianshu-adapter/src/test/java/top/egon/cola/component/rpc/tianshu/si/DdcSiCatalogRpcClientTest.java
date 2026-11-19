package top.egon.cola.component.rpc.tianshu.si;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.ConnectivityState;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiResourceProto;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiSearchHitProto;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.model.si.DdcSiCatalogQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiSearchHit;
import top.egon.cola.component.tianshu.model.si.DdcSiSelfRegistrationRequest;
import top.egon.cola.component.tianshu.model.si.DdcSiSemanticSearchQuery;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdcSiCatalogRpcClientTest {

    private Server server;
    private ManagedChannel channel;
    private DdcSiCatalogRpcClient client;
    private volatile boolean simulateSlowSearch;

    @BeforeEach
    void setUp() throws Exception {
        DdcSiProtoMapper mapper = Mappers.getMapper(DdcSiProtoMapper.class);
        DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase serviceImpl =
                new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase() {
                    @Override
                    public void listSiCatalog(
                            ListSiCatalogRequest request,
                            StreamObserver<ListSiCatalogResponse> observer) {
                        observer.onNext(ListSiCatalogResponse.newBuilder()
                                .addItems(sampleProto(request.getTenantId(), "agent.one"))
                                .setTotal(1)
                                .build());
                        observer.onCompleted();
                    }

                    @Override
                    public void resolveSiResource(
                            ResolveSiResourceRequest request,
                            StreamObserver<ResolveSiResourceResponse> observer) {
                        if ("missing.key".equals(request.getResourceKey())) {
                            observer.onError(Status.NOT_FOUND.withDescription("not found").asRuntimeException());
                            return;
                        }
                        if (request.getTenantId() == 9999L) {
                            observer.onError(Status.PERMISSION_DENIED.withDescription("tenant mismatch").asRuntimeException());
                            return;
                        }
                        observer.onNext(ResolveSiResourceResponse.newBuilder()
                                .setResource(sampleProto(request.getTenantId(), request.getResourceKey()))
                                .build());
                        observer.onCompleted();
                    }

                    @Override
                    public void semanticSearchSiResources(
                            SemanticSearchSiRequest request,
                            StreamObserver<SemanticSearchSiResponse> observer) {
                        if (simulateSlowSearch) {
                            try {
                                Thread.sleep(300L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        observer.onNext(SemanticSearchSiResponse.newBuilder()
                                .addHits(SiSearchHitProto.newBuilder()
                                        .setResource(sampleProto(request.getTenantId(), "mcp.search"))
                                        .setHybridScore(0.88)
                                        .setVectorScore(0.90)
                                        .setFtsScore(0.80)
                                        .setMatchedChunkText("search match")
                                        .setDegradedToFtsOnly(true)
                                        .setRank(1)
                                        .build())
                                .build());
                        observer.onCompleted();
                    }

                    @Override
                    public void registerManagedSiResource(
                            RegisterManagedSiRequest request,
                            StreamObserver<RegisterManagedSiResponse> observer) {
                        observer.onNext(RegisterManagedSiResponse.newBuilder()
                                .setResource(sampleProto(request.getTenantId(), request.getResourceKey()))
                                .build());
                        observer.onCompleted();
                    }
                };

        server = NettyServerBuilder.forPort(0).addService(serviceImpl).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext()
                .disableRetry().disableServiceConfigLookUp().build();
        awaitReady(channel);
        client = new DdcSiCatalogRpcClient(
                DdcSiResourceGrpcServiceGrpc.newBlockingStub(channel),
                mapper,
                Duration.ofMillis(150));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void executesAllFourRpcsAndMapsNotFoundPermissionDeniedAndDeadline() {
        List<DdcSiResourceDescriptor> catalog = client.listResources(DdcSiCatalogQuery.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .build());
        assertThat(catalog).hasSize(1);
        assertThat(catalog.get(0).getResourceKey()).isEqualTo("agent.one");

        Optional<DdcSiResourceDescriptor> resolved = client.resolveResource(
                1001L, "xingyuan", "default", "prod", "tianji", DdcSiKindEnum.AGENT, "agent.found", null);
        assertThat(resolved).isPresent();

        Optional<DdcSiResourceDescriptor> missing = client.resolveResource(
                1001L, "xingyuan", "default", "prod", "tianji", DdcSiKindEnum.AGENT, "missing.key", null);
        assertThat(missing).isEmpty();

        assertThatThrownBy(() -> client.resolveResource(
                9999L, "xingyuan", "default", "prod", "tianji", DdcSiKindEnum.AGENT, "agent.found", null))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        List<DdcSiSearchHit> hits = client.semanticSearch(DdcSiSemanticSearchQuery.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .queryText("search tool")
                .build());
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).isDegradedToFtsOnly()).isTrue();

        DdcSiResourceDescriptor registered = client.registerManagedResource(DdcSiSelfRegistrationRequest.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("dev")
                .serviceName("svc")
                .instanceId("inst")
                .kind(DdcSiKindEnum.AGENT)
                .resourceKey("agent.self")
                .displayName("Self Agent")
                .specJson("{}")
                .build());
        assertThat(registered.getResourceKey()).isEqualTo("agent.self");

        simulateSlowSearch = true;
        assertThatThrownBy(() -> client.semanticSearch(DdcSiSemanticSearchQuery.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .queryText("slow")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    @Test
    void registersConditionalMapperInApplicationContextRunner() {
        new ApplicationContextRunner()
                .withBean(DdcSiProtoMapper.class, () -> Mappers.getMapper(DdcSiProtoMapper.class))
                .run(context -> assertThat(context).hasSingleBean(DdcSiProtoMapper.class));
    }

    private static SiResourceProto sampleProto(long tenantId, String key) {
        return SiResourceProto.newBuilder()
                .setTenantId(tenantId)
                .setBizCode("xingyuan")
                .setNamespaceCode("default")
                .setEnvCode("prod")
                .setResourceId(10L)
                .setKind("AGENT")
                .setResourceKey(key)
                .setDisplayName(key)
                .setStatus("PUBLISHED")
                .setSourceType("CONSOLE")
                .setActiveVersionNo(1)
                .setPublishedEpochMillis(1_763_280_930_000L)
                .setUpdatedEpochMillis(1_763_280_930_000L)
                .build();
    }

    private static void awaitReady(ManagedChannel channel) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            ConnectivityState state = channel.getState(true);
            if (state == ConnectivityState.READY) { return; }
            long remaining = end - System.nanoTime();
            if (remaining <= 0 || state == ConnectivityState.SHUTDOWN) {
                throw new AssertionError("Local SI channel did not become ready");
            }
            var changed = new CompletableFuture<Void>();
            channel.notifyWhenStateChanged(state, () -> changed.complete(null));
            changed.get(remaining, TimeUnit.NANOSECONDS);
        }
    }
}
