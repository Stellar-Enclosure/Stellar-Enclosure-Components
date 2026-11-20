package top.egon.cola.component.rpc.tianshu.si;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.*;
import top.egon.cola.component.rpc.consumer.generic.*;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.reference.*;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.*;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.model.si.*;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DdcSiCatalogRpcClientTest {
    private static final DdcSiProtoMapper MAPPER=Mappers.getMapper(DdcSiProtoMapper.class);

    @Test void allFourRpcsUseOneNativeRuntimeAndExactServiceCredentials() throws Exception {
        var observed=new CopyOnWriteArrayList<String>();
        var attempts=new CopyOnWriteArrayList<String>();
        var server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(ServerInterceptors.intercept(new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase(){
            @Override public void listSiCatalog(ListSiCatalogRequest request,StreamObserver<ListSiCatalogResponse> response){
                response.onNext(ListSiCatalogResponse.newBuilder().addItems(sample("agent.one")).build());response.onCompleted();
            }
            @Override public void resolveSiResource(ResolveSiResourceRequest request,StreamObserver<ResolveSiResourceResponse> response){
                if(request.getResourceKey().equals("missing")){response.onError(Status.NOT_FOUND.asRuntimeException());return;}
                response.onNext(ResolveSiResourceResponse.newBuilder().setResource(sample("agent.found")).build());response.onCompleted();
            }
            @Override public void semanticSearchSiResources(SemanticSearchSiRequest request,StreamObserver<SemanticSearchSiResponse> response){
                response.onNext(SemanticSearchSiResponse.newBuilder().addHits(SiSearchHitProto.newBuilder().setResource(sample("agent.search")).setRank(1)).build());response.onCompleted();
            }
            @Override public void registerManagedSiResource(RegisterManagedSiRequest request,StreamObserver<RegisterManagedSiResponse> response){
                response.onNext(RegisterManagedSiResponse.newBuilder().setResource(sample("agent.registered")).build());response.onCompleted();
            }
        },new ServerInterceptor(){
            @Override public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next){
                var values=headers.getAll(RpcMetadataKeys.AUTHORIZATION);
                observed.add(values==null?"missing":String.join(",",values));
                attempts.add(headers.get(RpcMetadataKeys.ATTEMPT));
                return next.startCall(call,headers);
            }
        })).build().start();
        try(var runtime=new NativeRuntime(server,DdcSiCatalogRpcClient.METHODS)){
            var client=runtime.client(Duration.ofSeconds(2));
            assertThat(client.listResources(DdcSiCatalogQuery.builder().tenantId(101L).bizCode("reader").namespaceCode("core").envCode("test").build())).hasSize(1);
            assertThat(client.resolveResource(101,"reader","core","test",null,DdcSiKindEnum.AGENT,"planner",null)).isPresent();
            assertThat(client.resolveResource(101,"reader","core","test",null,DdcSiKindEnum.AGENT,"missing",null)).isEmpty();
            assertThat(client.semanticSearch(DdcSiSemanticSearchQuery.builder().tenantId(101L).bizCode("reader").namespaceCode("core").envCode("test").queryText("agent").build())).hasSize(1);
            assertThat(client.registerManagedResource(DdcSiSelfRegistrationRequest.builder().tenantId(101L).bizCode("reader").namespaceCode("core").envCode("test")
                    .kind(DdcSiKindEnum.AGENT).resourceKey("planner").specJson("{}").build()).getResourceKey()).isEqualTo("agent.registered");
            assertThat(observed).hasSize(5).allSatisfy(value->assertThat(value).isEqualTo("Bearer si-fixture-token"));
            assertThat(attempts).containsOnly("1");
            verify(runtime.oauth,times(5)).authorize(any());
        }finally{server.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);}
    }

    @Test void unapprovedMethodFailsBeforeCredentialsOrTransportAndWritesAreNeverReplayed() throws Exception {
        var calls=new AtomicInteger();
        var server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase(){
            @Override public void registerManagedSiResource(RegisterManagedSiRequest request,StreamObserver<RegisterManagedSiResponse> response){calls.incrementAndGet();response.onError(Status.UNAVAILABLE.asRuntimeException());}
        }).build().start();
        try(var runtime=new NativeRuntime(server,Set.of(DdcSiCatalogRpcClient.REGISTER))){
            var client=runtime.client(Duration.ofSeconds(2));
            assertThatThrownBy(()->client.listResources(new DdcSiCatalogQuery())).isInstanceOfSatisfying(StatusRuntimeException.class,
                    failure->assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            verifyNoInteractions(runtime.oauth);
            assertThatThrownBy(()->client.registerManagedResource(DdcSiSelfRegistrationRequest.builder().tenantId(101L).kind(DdcSiKindEnum.AGENT).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,failure->assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            assertThat(calls).hasValue(1);
        }finally{server.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);}
    }

    @Test void genericWireDigestMatchesProviderCanonicalDigestForUnorderedMapFields() {
        RpcGenericInvoker invoker=mock(RpcGenericInvoker.class);
        when(invoker.invokeBlocking(any())).thenAnswer(call->{
            var invocation=(RpcGenericInvocation)call.getArgument(0);
            var request=ListSiCatalogRequest.parseFrom(invocation.requestPayload());
            assertThat(RpcInvocationMetadata.requestDigest(invocation.requestPayload()))
                    .isEqualTo(RpcInvocationMetadata.canonicalRequestDigest(request));
            return ListSiCatalogResponse.newBuilder().addItems(sample("mapped")).build().toByteArray();
        });
        var target=new RpcServiceTargetVO("si",1,"platform-prod","tianshu-si-rpc",19080,Set.of(DdcSiCatalogRpcClient.LIST));
        var client=new DdcSiCatalogRpcClient(invoker,MAPPER,target,Duration.ofSeconds(2));
        var labels=new LinkedHashMap<String,String>();labels.put("z-last","z");labels.put("a-first","a");
        assertThat(client.listResources(DdcSiCatalogQuery.builder().tenantId(101L).bizCode("reader").namespaceCode("core")
                .envCode("test").labels(labels).build())).hasSize(1);
    }

    @Test void executionBundleUsesTheSameProtectedNativeMethodWithExactOptInAndNoMetadataFallback() throws Exception {
        var body="{}";
        var digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var root=new DdcSiDependencyManifestVO.VersionIdentityVO("101","441","801","3",DdcSiKindEnum.AGENT,"test",digest);
        var child=new DdcSiDependencyManifestVO.VersionIdentityVO("101","442","802","2",DdcSiKindEnum.PROMPT,"test",digest);
        var manifest=DdcSiDependencyManifestVO.create(root,null,List.of(child),List.of(new DdcSiDependencyManifestVO.EdgeVO("801","802")));
        var bundle=new DdcSiExecutionBundleVO(new DdcSiExecutionBundleVO.MaterialVO(root,"reader","core",null,"planner",body),manifest,
                List.of(new DdcSiExecutionBundleVO.MaterialVO(child,"reader","core","prompt-owner","instructions",body)));
        var calls=new AtomicInteger();
        var server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase(){
            @Override public void resolveSiResource(ResolveSiResourceRequest request,StreamObserver<ResolveSiResourceResponse> response){
                assertThat(request.getExecutionRequired()).isTrue();assertThat(request.getIncludeExecutionMaterial()).isTrue();
                assertThat(request.hasOwnerAppCode()).isFalse();assertThat(request.getCallerAppCode()).isEqualTo("si-reader");
                assertThat(request.getExactVersionNo()).isEqualTo(3);assertThat(request.getRequestId()).isEmpty();
                response.onNext(calls.incrementAndGet()==1?ResolveSiResourceResponse.newBuilder().setExecutionBundle(MAPPER.toProto(bundle)).build()
                        :ResolveSiResourceResponse.newBuilder().setResource(sample("planner")).build());response.onCompleted();
            }
        }).build().start();
        try(var runtime=new NativeRuntime(server,Set.of(DdcSiCatalogRpcClient.RESOLVE))){
            var client=runtime.client(Duration.ofSeconds(2));
            assertThat(client.resolveExecutionBundle(101,"reader","core","test",null,"si-reader",DdcSiKindEnum.AGENT,"planner",3L)).contains(bundle);
            assertThatThrownBy(()->client.resolveExecutionBundle(101,"reader","core","test",null,"si-reader",DdcSiKindEnum.AGENT,"planner",3L))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,failure->assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
            assertThatThrownBy(()->client.resolveExecutionBundle(101,"reader","core","test",null,"si-reader",DdcSiKindEnum.PROMPT,"planner",3L)).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).hasValue(2);verify(runtime.oauth,times(2)).authorize(any());
        }finally{server.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);}
    }

    private static SiResourceProto sample(String key){return SiResourceProto.newBuilder().setTenantId(101).setBizCode("reader").setNamespaceCode("core").setEnvCode("test")
            .setResourceId(441).setKind("AGENT").setResourceKey(key).setStatus("PUBLISHED").setSourceType("CONSOLE").setActiveVersionNo(1).build();}

    /** Shared real TCP fixture; production authority remains the validated .svc name. */
    public static final class NativeRuntime implements AutoCloseable {
        public static final RpcProcessIdentity PROCESS=new RpcProcessIdentity("si-reader","test","fixture",1L,"fixture");
        public final IdpServiceOAuth2Client oauth=mock(IdpServiceOAuth2Client.class);
        public final RpcServiceTargetVO target;
        public final RpcGenericInvoker invoker;
        private final RpcConsumerChannelPool pool;
        private final RpcReferenceStrategyFactory strategies;
        private final RpcGenericTargetCache cache;
        private final RpcInvocationExecutor executor;
        private final jakarta.validation.ValidatorFactory validators=Validation.buildDefaultValidatorFactory();

        public NativeRuntime(Server server,Set<String> methods) throws Exception {
            target=new RpcServiceTargetVO("si",1,"platform-prod","tianshu-si-rpc",19080,methods);
            pool=new RpcConsumerChannelPool(new RpcConsumerChannelFactory(4*1024*1024,1000){
                @Override public ManagedChannel create(RpcChannelKey key){
                    return NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).overrideAuthority(target.authority()).usePlaintext()
                            .disableRetry().disableServiceConfigLookUp().build();
                }
            },Duration.ofSeconds(5));
            var properties=rpcProperties(methods);
            strategies=new RpcReferenceStrategyFactory(pool);
            cache=new RpcGenericTargetCache(new RpcReferenceDefinitionResolver(properties),strategies,8,Duration.ofSeconds(30));
            executor=new RpcInvocationExecutor(new RpcStatusExceptionMapper());
            when(oauth.authorize(any())).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"si-fixture-token",Instant.now().minusSeconds(1),Instant.now().plusSeconds(60),Set.of("fixture:si")));
            var credentials=IdpRpcClientCredentialInterceptorFactory.createConfigured(oauth,identityProperties(methods),validators.getValidator(),
                    (invocation,authentication)->Optional.of("101"));
            invoker=new RpcGenericInvoker(cache,executor,PROCESS,List.of(credentials));
            try(var warm=pool.acquire(target)){awaitReady(warm.channel());}
        }
        public DdcSiCatalogRpcClient client(Duration deadline){return new DdcSiCatalogRpcClient(invoker,MAPPER,target,deadline);}
        @Override public void close(){executor.close();cache.close();strategies.close();pool.close();validators.close();}
    }

    public static EgonRpcProperties rpcProperties(Set<String> methods){
        var properties=new EgonRpcProperties();
        properties.getConsumer().setDefaultTimeoutMs(5000).setBindings(Map.of("si",new EgonRpcProperties.RpcServiceBindingConfig()
                .setVersion(1).setNamespace("platform-prod").setService("tianshu-si-rpc").setPort(19080).setMethods(methods)));
        return properties;
    }
    public static IdpStarterProperties identityProperties(Set<String> methods){
        var properties=new IdpStarterProperties();properties.getRpc().setEnabled(true);properties.getRpc().getConsumer().setEnabled(true);
        Map<String,IdpStarterProperties.RpcConsumerBindingProperties> bindings=new LinkedHashMap<>();int i=0;
        for(String method:methods){bindings.put("method"+(i++),new IdpStarterProperties.RpcConsumerBindingProperties()
                .setServiceName(DdcSiCatalogRpcClient.SERVICE).setGroup("tianshu").setVersion("1.0.0").setFullMethodName(method)
                .setProcessIdentity(Map.of("applicationName",NativeRuntime.PROCESS.applicationName(),"env",NativeRuntime.PROCESS.env()))
                .setMode(IdpStarterProperties.RpcConsumerModeEnum.SERVICE).setScopeContext(ServiceTokenContext.TENANT)
                .setAppId("si-reader-app").setResourceUri(URI.create("https://tianshu.example.test")).setScopes(Set.of("fixture:si"))
                .setTenantRegistrationIds(Map.of("101","si-reader-101")).setTransportBindingId("si").setTransportBindingVersion(1L));}
        properties.getRpc().getConsumer().setBindings(bindings);return properties;
    }
    private static void awaitReady(ManagedChannel channel) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){var state=channel.getState(true);if(state==ConnectivityState.READY)return;
            long remaining=end-System.nanoTime();if(remaining<=0)throw new AssertionError("local SI channel not ready");
            var changed=new CompletableFuture<Void>();channel.notifyWhenStateChanged(state,()->changed.complete(null));changed.get(remaining,TimeUnit.NANOSECONDS);}
    }
}
