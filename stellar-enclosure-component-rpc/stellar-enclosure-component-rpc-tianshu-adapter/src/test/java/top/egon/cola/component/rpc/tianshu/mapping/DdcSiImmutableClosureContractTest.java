package top.egon.cola.component.rpc.tianshu.mapping;

import io.grpc.ConnectivityState;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.*;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiDependencyManifestVO;
import top.egon.cola.component.tianshu.model.si.DdcSiDependencyManifestVO.VersionIdentityVO;
import top.egon.cola.component.tianshu.model.si.DdcSiDependencyManifestVO.EdgeVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;

class DdcSiImmutableClosureContractTest {
    private static final DdcSiProtoMapper MAPPER=Mappers.getMapper(DdcSiProtoMapper.class);
    private static final String BODY="{ \"prompt\" : \"你好🌌\" }\n";

    @Test void fixedVersionOneCannotAcceptAResponseContainingVersionTwoOrRetryToCurrent() throws Exception {
        try(var fixture=Fixture.responding(legacy(2))) {
            assertHistoryFailure(catchThrowable(() -> fixture.client().resolveResource(101,"reader","core","test","owner-app",
                    DdcSiKindEnum.AGENT,"planner",1)));
            assertThat(fixture.received).hasValue(1);
            assertThat(fixture.request.get().getPinnedVersionNo()).isEqualTo(1);
        }
    }

    @Test void legacyVersionOneWithoutAnImmutableManifestCannotBecomeExecutableMaterial() throws Exception {
        try(var fixture=Fixture.responding(legacy(1))) {
            assertHistoryFailure(catchThrowable(() -> resolve(fixture,"owner-app",1L)));
            assertThat(fixture.received).hasValue(1);
        }
    }

    @Test void validRawUtf8BodyAndBigintVersionsKeepOwnerSeparateFromCallerWithoutLegacySelectorFallback() throws Exception {
        var response=resource("owner-app",Long.MAX_VALUE,BODY);
        try(var fixture=Fixture.responding(response)) {
            var result=resolve(fixture,"owner-app",Long.MAX_VALUE).orElseThrow();
            assertThat(result.getOwnerAppCode()).isEqualTo("owner-app");
            assertThat(result.getResolvedVersionNo()).isEqualTo(Long.MAX_VALUE);
            assertThat(result.getActiveVersionNo()).isEqualTo(5);
            assertThat(result.getSpecJson()).isEqualTo(BODY);
            assertThat(result.getDependencyManifest().nodes()).isEmpty();
            var request=fixture.request.get();
            assertThat(request.getOwnerAppCode()).isEqualTo("owner-app");
            assertThat(request.getCallerAppCode()).isEqualTo("caller-app");
            assertThat(request.hasExactVersionNo()).isTrue();
            assertThat(request.getExactVersionNo()).isEqualTo(Long.MAX_VALUE);
            assertThat(request.getExecutionRequired()).isTrue();
            assertThat(request.getAppCode()).isEmpty();
            assertThat(request.getPinnedVersionNo()).isZero();
            assertThat(fixture.received).hasValue(1);
        }
    }

    @Test void namespaceOwnerAbsenceAndExplicitEmptyGraphHaveIndependentPresence() throws Exception {
        var response=resource(null,1,BODY);
        assertThat(response.hasOwnerAppCode()).isFalse();
        assertThat(response.getDependencyManifest().hasNodes()).isTrue();
        assertThat(response.getDependencyManifest().hasEdges()).isTrue();
        try(var fixture=Fixture.responding(response)) {
            assertThat(resolve(fixture,null,null).orElseThrow().getOwnerAppCode()).isNull();
            assertThat(fixture.request.get().hasOwnerAppCode()).isFalse();
            assertThat(fixture.request.get().hasExactVersionNo()).isFalse();
        }
    }

    @Test void missingGraphWrappersAreNotDecodedAsVerifiedEmptyArrays() {
        var complete=resource("owner-app",1,BODY);
        for(var manifest:List.of(complete.getDependencyManifest().toBuilder().clearNodes().build(),
                complete.getDependencyManifest().toBuilder().clearEdges().build(),
                complete.getDependencyManifest().toBuilder().clearRoot().build())) {
            assertThatThrownBy(() -> MAPPER.fromProto(complete.toBuilder().setDependencyManifest(manifest).build()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MAPPER.fromProto(complete).getDependencyManifest().nodes()).isEmpty();
        assertThat(MAPPER.fromProto(complete).getDependencyManifest().source()).isNull();
    }

    @Test void immutableDagSourceAndCanonicalDigestRoundTripWithoutReorderingOrTruncation() {
        var root=identity("9007199254740993","1",DdcSiKindEnum.AGENT,sha(BODY));
        var first=identity("9007199254740994","2",DdcSiKindEnum.PROMPT,"a".repeat(64));
        var second=identity("9007199254740995","3",DdcSiKindEnum.SKILL,"b".repeat(64));
        var source=new VersionIdentityVO("101","444","9007199254740996","4",DdcSiKindEnum.AGENT,"test","c".repeat(64));
        var manifest=DdcSiDependencyManifestVO.create(root,source,List.of(second,first),
                List.of(new EdgeVO(first.versionId(),second.versionId()),new EdgeVO(root.versionId(),first.versionId())));
        var proto=MAPPER.toProto(manifest);
        var decoded=MAPPER.fromProto(proto);
        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.canonicalBytes()).isEqualTo(manifest.canonicalBytes());
        assertThat(decoded.nodes()).extracting(VersionIdentityVO::versionId).containsExactly(first.versionId(),second.versionId());
        assertThatThrownBy(() -> decoded.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> MAPPER.fromProto(proto.toBuilder().setClosureDigest("d".repeat(64)).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void selectedVersionRootCoordinatesOwnerAndRawBodyMustAllMatch() throws Exception {
        var valid=resource("owner-app",1,BODY);
        for(var response:List.of(resource("owner-app",2,BODY), valid.toBuilder().setOwnerAppCode("other-owner").build(),
                valid.toBuilder().setTenantId(102).build(),valid.toBuilder().setEnvCode("prod").build(),
                valid.toBuilder().setResolvedVersionId(999).build(),valid.toBuilder().setResolvedVersionNo(2).build(),
                valid.toBuilder().setSpecJson("{\"prompt\":\"你好🌌\"}").build(),
                valid.toBuilder().setStatus("DRAFT").build(),valid.toBuilder().setOwnerAppCode("").build())) {
            try(var fixture=Fixture.responding(response)) {
                assertHistoryFailure(catchThrowable(() -> resolve(fixture,"owner-app",1L)));
                assertThat(fixture.received).hasValue(1);
            }
        }
    }

    @Test void attestedInvalidJsonObjectCannotBecomeExecutionMaterial() throws Exception {
        for(String body:List.of("[]","{} {}","{\"x\":1,\"x\":2}")) {
            try(var fixture=Fixture.responding(resource("owner-app",1,body))) {
                assertHistoryFailure(catchThrowable(() -> resolve(fixture,"owner-app",1L)));
            }
        }
    }

    @Test void permanentHistoryFailureIsDistinctFromPermissionAndTransientServiceFailures() throws Exception {
        for(Status status:List.of(Status.NOT_FOUND,Status.PERMISSION_DENIED,Status.UNAVAILABLE)) {
            try(var fixture=new Fixture((request,observer)->observer.onError(status.withDescription("synthetic").asRuntimeException()))) {
                Throwable failure=catchThrowable(() -> resolve(fixture,"owner-app",1L));
                if(status.getCode()==Status.Code.NOT_FOUND) { assertHistoryFailure(failure); }
                else { assertThat(failure).isInstanceOfSatisfying(StatusRuntimeException.class,
                        actual->assertThat(actual.getStatus().getCode()).isEqualTo(status.getCode())); }
                assertThat(fixture.received).hasValue(1);
            }
        }
    }

    @Test void blankCallerPresentEmptyOwnerAndInvalidVersionRejectBeforeTransport() throws Exception {
        try(var fixture=Fixture.responding(resource("owner-app",1,BODY))) {
            var client=fixture.client();
            assertThatThrownBy(() -> client.resolveExecutionResource(101,"reader","core","test","owner-app"," ",DdcSiKindEnum.AGENT,"planner",1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolve(fixture,"",1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolve(fixture,"owner-app",0L)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.received).hasValue(0);
        }
    }

    @Test void clientInheritsExecutableValidationWithoutRedefiningPortConstraints() throws Exception {
        try(var fixture=Fixture.responding(resource("owner-app",1,BODY));
                var validators=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var client=fixture.client();
            var method=DdcSiCatalogRpcClient.class.getMethod("resolveExecutionResource",long.class,String.class,
                    String.class,String.class,String.class,String.class,DdcSiKindEnum.class,String.class,Long.class);
            var violations=validators.getValidator().forExecutables().validateParameters(client,method,
                    new Object[]{101L,"reader","core","test","owner-app"," ",null,"planner",1L});
            assertThat(violations).hasSize(2);
            assertThat(fixture.received).hasValue(0);
        }
    }

    @Test void strictCallPreservesAnExistingShortDeadlineAndCancelsTheRealServerCallOnce() throws Exception {
        var cancelled=new CountDownLatch(1);
        try(var fixture=new Fixture((request,observer)->((ServerCallStreamObserver<?>)observer).setOnCancelHandler(cancelled::countDown))) {
            var client=new DdcSiCatalogRpcClient(DdcSiResourceGrpcServiceGrpc.newBlockingStub(fixture.channel)
                    .withDeadlineAfter(250,TimeUnit.MILLISECONDS),MAPPER,Duration.ofSeconds(2));
            var failure=catchThrowable(() -> client.resolveExecutionResource(101,"reader","core","test","owner-app","caller-app",DdcSiKindEnum.AGENT,"planner",1L));
            assertThat(failure).isInstanceOfSatisfying(StatusRuntimeException.class,
                    actual->assertThat(actual.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
            assertThat(cancelled.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.observedDeadlineMillis.get()).isLessThanOrEqualTo(250);
            assertThat(fixture.received).hasValue(1);
        }
    }

    @Test void parentCancellationDoesNotLaunchAnotherPhysicalAttempt() throws Exception {
        var cancelled=new CountDownLatch(1);
        try(var fixture=new Fixture((request,observer)->((ServerCallStreamObserver<?>)observer).setOnCancelHandler(cancelled::countDown));
                var executor=Executors.newVirtualThreadPerTaskExecutor();
                var context=Context.current().withCancellation()) {
            var result=CompletableFuture.supplyAsync(()->catchThrowable(()->context.call(()->resolve(fixture,"owner-app",1L))),executor);
            assertThat(fixture.arrived.await(3,TimeUnit.SECONDS)).isTrue();
            context.cancel(null);
            assertThat(result.get(3,TimeUnit.SECONDS)).isInstanceOfSatisfying(StatusRuntimeException.class,
                    actual->assertThat(actual.getStatus().getCode()).isEqualTo(Status.Code.CANCELLED));
            assertThat(cancelled.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.received).hasValue(1);
        }
    }

    @Test void newFieldsAppendToTheExistingWireNumbersAndUsePresenceForExactBigintVersion() {
        var request=ResolveSiResourceRequest.getDescriptor();
        assertThat(request.findFieldByName("app_code").getNumber()).isEqualTo(5);
        assertThat(request.findFieldByName("pinned_version_no").getNumber()).isEqualTo(8);
        assertThat(request.findFieldByName("request_id").getNumber()).isEqualTo(9);
        assertThat(request.findFieldByName("owner_app_code").getNumber()).isEqualTo(10);
        assertThat(request.findFieldByName("exact_version_no").hasPresence()).isTrue();
        assertThat(SiResourceProto.getDescriptor().findFieldByName("updated_epoch_millis").getNumber()).isEqualTo(18);
        assertThat(SiResourceProto.getDescriptor().findFieldByName("dependency_manifest").getNumber()).isEqualTo(22);
    }

    private static java.util.Optional<top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor> resolve(Fixture fixture,String owner,Long version) {
        return fixture.client().resolveExecutionResource(101,"reader","core","test",owner,"caller-app",DdcSiKindEnum.AGENT,"planner",version);
    }
    private static void assertHistoryFailure(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(StatusRuntimeException.class,actual->{
            assertThat(actual.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(actual.getStatus().getDescription()).isEqualTo("SI_PINNED_HISTORY_UNAVAILABLE");
        });
    }
    private static SiResourceProto resource(String owner,long version,String body) {
        var root=identity("9007199254740993",Long.toString(version),DdcSiKindEnum.AGENT,sha(body));
        var manifest=DdcSiDependencyManifestVO.create(root,null,List.of(),List.of());
        var builder=legacy(5).toBuilder().setSpecJson(body).setDigestSha256(sha(body))
                .setResolvedVersionId(Long.parseLong(root.versionId())).setResolvedVersionNo(version)
                .setDependencyManifest(MAPPER.toProto(manifest));
        if(owner!=null) builder.setOwnerAppCode(owner);
        return builder.build();
    }
    private static SiResourceProto legacy(int version) {
        return SiResourceProto.newBuilder().setTenantId(101).setBizCode("reader").setNamespaceCode("core").setEnvCode("test")
                .setResourceId(441).setKind("AGENT").setResourceKey("planner").setDisplayName("Planner")
                .setStatus("PUBLISHED").setSourceType("CONSOLE").setActiveVersionNo(version).setSpecJson("{}").setDigestSha256(sha("{}")).build();
    }
    private static VersionIdentityVO identity(String id,String version,DdcSiKindEnum kind,String digest) {
        String resource=kind==DdcSiKindEnum.PROMPT?"442":kind==DdcSiKindEnum.SKILL?"443":"441";
        return new VersionIdentityVO("101",resource,id,version,kind,"test",digest);
    }
    private static String sha(String body) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible) {throw new AssertionError(impossible);}
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger received=new AtomicInteger();
        final AtomicReference<ResolveSiResourceRequest> request=new AtomicReference<>();
        final AtomicLong observedDeadlineMillis=new AtomicLong(Long.MIN_VALUE);
        final CountDownLatch arrived=new CountDownLatch(1);
        final Server server;
        final ManagedChannel channel;
        Fixture(BiConsumer<ResolveSiResourceRequest,StreamObserver<ResolveSiResourceResponse>> response) throws Exception {
            server=NettyServerBuilder.forPort(0).addService(new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase(){
                @Override public void resolveSiResource(ResolveSiResourceRequest input,StreamObserver<ResolveSiResourceResponse> observer){
                    received.incrementAndGet();request.set(input);
                    if(Context.current().getDeadline()!=null) observedDeadlineMillis.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                    arrived.countDown();response.accept(input,observer);
                }
            }).build().start();
            channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().disableRetry().disableServiceConfigLookUp().build();
            awaitReady(channel);
        }
        static Fixture responding(SiResourceProto resource) throws Exception {
            return new Fixture((request,observer)->{observer.onNext(ResolveSiResourceResponse.newBuilder().setResource(resource).build());observer.onCompleted();});
        }
        DdcSiCatalogRpcClient client(){return new DdcSiCatalogRpcClient(DdcSiResourceGrpcServiceGrpc.newBlockingStub(channel),MAPPER,Duration.ofSeconds(2));}
        @Override public void close() throws Exception {channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    }
    static void awaitReady(ManagedChannel channel) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){
            ConnectivityState state=channel.getState(true);
            if(state==ConnectivityState.READY)return;
            long remaining=end-System.nanoTime();
            if(remaining<=0 || state==ConnectivityState.SHUTDOWN)throw new AssertionError("Local SI channel did not become ready");
            var changed=new CompletableFuture<Void>();channel.notifyWhenStateChanged(state,()->changed.complete(null));
            changed.get(remaining,TimeUnit.NANOSECONDS);
        }
    }
}
