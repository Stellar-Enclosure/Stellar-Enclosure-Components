package top.egon.cola.component.rpc.provider.server;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.WrappersProto;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.MethodDescriptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.accessguard.api.RateLimitGuard;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.RateLimitDecision;
import top.egon.cola.component.accessguard.store.RateLimitRequest;
import top.egon.cola.component.accessguard.store.redisson.AccessGuardRedisKeyFactory;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcProvider;
import top.egon.cola.component.rpc.annotation.EgonRpcService;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.config.RpcAccessGuardAutoConfiguration;
import top.egon.cola.component.rpc.consumer.interceptor.RpcConsumerClientInterceptor;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcFailureStage;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.descriptor.RpcMethodDescriptor;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBeanScanner;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.support.TestGrpcDescriptorFixtures.UnaryFixtureGrpc;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcProviderAccessGuardComponentTest {

    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            Jackson2AutoConfiguration.class,
                            top.egon.cola.component.accessguard.autoconfigure
                                    .AccessGuardCoreAutoConfiguration.class,
                            top.egon.cola.component.accessguard.autoconfigure
                                    .AccessGuardLocalStoreAutoConfiguration.class,
                            top.egon.cola.component.accessguard.autoconfigure
                                    .AccessGuardTimeLimitAutoConfiguration.class,
                            top.egon.cola.component.accessguard.autoconfigure
                                    .AccessGuardAopAutoConfiguration.class,
                            RpcAccessGuardAutoConfiguration.class))
                    .withUserConfiguration(ProviderConfiguration.class)
                    .withPropertyValues(
                            "egon.cola.component.rpc.provider.enabled=true",
                            "egon.cola.component.access-guard.enabled=true",
                            "egon.cola.component.access-guard.key.hmac-secret=test-secret",
                            "egon.cola.component.access-guard.key.contributors[0]=GLOBAL",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.enabled=true",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.algorithm=TOKEN_BUCKET",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.capacity=1",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.refill-tokens=1",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.refill-period=PT1H",
                            "egon.cola.component.access-guard.rules.rpc-create.rate-limit.requested-tokens=1");

    @Test
    void rateLimitGuardRejectsBeforeBusinessAndMapsResourceExhausted()
            throws Exception {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GuardedProvider provider = context.getBean(GuardedProvider.class);
            RpcProviderMethodRegistry registry = new RpcProviderBeanScanner(
                    context,
                    new RpcContractValidator(VALIDATION_UTILS)).scan();
            RpcProviderAvailabilityRegistry availability =
                    new RpcProviderAvailabilityRegistry();
            registry.providers().forEach(binding ->
                    availability.available(binding.serviceIdentity()));
            RpcServerServiceDefinitionFactory definitions =
                    new RpcServerServiceDefinitionFactory(
                            availability,
                            List.of(context.getBean(
                                    RpcAccessGuardExceptionMapper.class)));
            Server server = null;
            ManagedChannel channel = null;
            try {
                var service = definitions.create(registry).getFirst();
                server = NettyServerBuilder.forPort(0)
                        .addService(service)
                        .build()
                        .start();
                channel = ManagedChannelBuilder.forAddress(
                                "127.0.0.1", server.getPort())
                        .usePlaintext()
                        .build();
                var method = new RpcContractValidator(VALIDATION_UTILS)
                        .validate(EchoContract.class)
                        .methods()
                        .getFirst()
                        .grpcMethod();

                @SuppressWarnings("unchecked")
                Message first = ClientCalls.blockingUnaryCall(
                        channel,
                        (io.grpc.MethodDescriptor<Message, Message>)
                                (io.grpc.MethodDescriptor<?, ?>) method,
                        CallOptions.DEFAULT,
                        StringValue.of("first"));
                assertThat(first).isEqualTo(StringValue.of("provider:first"));
                assertThat(provider.calls()).isOne();

                ManagedChannel activeChannel = channel;
                assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                                activeChannel,
                                (io.grpc.MethodDescriptor<Message, Message>)
                                        (io.grpc.MethodDescriptor<?, ?>) method,
                                CallOptions.DEFAULT,
                                StringValue.of("second")))
                        .isInstanceOfSatisfying(
                                StatusRuntimeException.class,
                                failure -> assertRateLimitFailure(failure));
                assertThat(provider.calls()).isOne();
            } finally {
                if (channel != null) {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
                if (server != null) {
                    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        });
    }

    /** Real local h2c and parsed protobuf; the resolver/backend doubles do not establish real IdP or Redis behavior. */
    @Test
    void authenticatedPhysicalAttemptsShareOneLogicalDebitAndUseTheActualRequestDigest() throws Exception {
        try (LocalAdmissionFixture fixture = new LocalAdmissionFixture()) {
            assertThat(fixture.invoke("call-one", 1, "first", "a".repeat(64)))
                    .isEqualTo(StringValue.of("provider:first"));
            assertThat(fixture.invoke("call-one", 2, "first", "b".repeat(64)))
                    .isEqualTo(StringValue.of("provider:first"));
            assertThat(fixture.provider.calls).hasValue(2);
            assertThat(fixture.physicalRequests).hasSize(2);
            assertThat(fixture.logicalRequests).hasSize(2);
            assertThat(fixture.logicalDebits).hasValue(1);
            String actualDigest = RpcInvocationMetadata.canonicalRequestDigest(StringValue.of("first"));
            assertThat(fixture.logicalRequests).allSatisfy(command -> {
                assertThat(command.tenantId()).isEqualTo(42);
                assertThat(command.clientId()).isEqualTo("trusted-client");
                assertThat(command.resource()).isEqualTo("https://resource.example/rpc");
                assertThat(command.quota()).isEqualTo("identity-read");
                assertThat(command.fullMethodName()).isEqualTo(RpcReferencePolicy.APPROVED_READ_METHOD);
                assertThat(command.logicalCallId()).isEqualTo("call-one");
                assertThat(command.requestDigest()).isEqualTo(actualDigest);
            });
            assertThat(fixture.logicalRequests).extracting(LogicalQuotaAcquireCommand::attempt)
                    .containsExactly(1, 2);

            assertThatThrownBy(() -> fixture.invoke("call-one", 2, "different body", "a".repeat(64)))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS));
            assertThat(fixture.logicalRequests.getLast().requestDigest()).isNotEqualTo(actualDigest);
            assertThatThrownBy(() -> fixture.invoke("call-two", 1, "new logical call", "a".repeat(64)))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                        assertThat(failure.getTrailers().get(RpcMetadataKeys.ERROR_TYPE)).isEqualTo("quota");
                        assertThat(new RpcStatusExceptionMapper().map(failure).getRpcErrorCode())
                                .isEqualTo(EgonRpcErrorCode.RPC_RATE_LIMITED);
                    });
            assertThat(fixture.physicalRequests).hasSize(4);
            assertThat(fixture.logicalRequests).hasSize(4);
            assertThat(fixture.logicalDebits).hasValue(1);
            assertThat(fixture.provider.calls).hasValue(2);
        }
    }

    @Test
    void trustedIdentityRejectionPrecedesAnyPhysicalOrLogicalDebitOverLocalH2c() throws Exception {
        try (LocalAdmissionFixture fixture = new LocalAdmissionFixture()) {
            fixture.authenticated.set(false);
            assertThatThrownBy(() -> fixture.invoke("denied-call", 1, "request", "a".repeat(64)))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                        assertThat(failure.getMessage()).doesNotContain("sensitive authority details");
                    });
            assertThat(fixture.physicalRequests).isEmpty();
            assertThat(fixture.logicalRequests).isEmpty();
            assertThat(fixture.logicalDebits).hasValue(0);
            assertThat(fixture.provider.calls).hasValue(0);
        }
    }

    @Test
    void unavailableLogicalStoreFailsClosedWithoutExecutingBusinessOrExposingItsCause() throws Exception {
        try (LocalAdmissionFixture fixture = new LocalAdmissionFixture()) {
            fixture.storeUnavailable.set(true);
            assertThatThrownBy(() -> fixture.invoke("store-call", 1, "request", "a".repeat(64)))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                        assertThat(RpcFailureStage.from(failure.getTrailers())).contains(RpcFailureStage.PROVIDER);
                        assertThat(failure.getTrailers().get(RpcMetadataKeys.ERROR_TYPE))
                                .isEqualTo(RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE);
                        assertThat(failure.getMessage()).doesNotContain("sensitive Redis details");
                        assertThat(new RpcStatusExceptionMapper().map(failure).getRpcErrorCode())
                                .isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE);
                    });
            assertThat(fixture.physicalRequests).hasSize(1);
            assertThat(fixture.logicalRequests).hasSize(1);
            assertThat(fixture.logicalDebits).hasValue(0);
            assertThat(fixture.provider.calls).hasValue(0);
        }
    }

    private static final class LocalAdmissionFixture implements AutoCloseable {
        private final AtomicBoolean authenticated = new AtomicBoolean(true);
        private final AtomicBoolean storeUnavailable = new AtomicBoolean();
        private final List<RateLimitRequest> physicalRequests = new CopyOnWriteArrayList<>();
        private final List<LogicalQuotaAcquireCommand> logicalRequests = new CopyOnWriteArrayList<>();
        private final AtomicInteger logicalDebits = new AtomicInteger();
        private final MembershipProvider provider = new MembershipProvider();
        private final AccessGuardRedisKeyFactory keys = new AccessGuardRedisKeyFactory("fixture:guard", "identity");
        private final Map<String, LogicalQuotaAcquireCommand> admitted = new LinkedHashMap<>();
        private final Map<String, Long> remainingByScope = new LinkedHashMap<>();
        private final MethodDescriptor<Message, Message> method;
        private final Server server;
        private final ManagedChannel channel;

        private LocalAdmissionFixture() throws Exception {
            var policy = new EgonRpcProperties.RpcProviderMethodAdmissionConfig()
                    .setQuota("identity-read").setQuotaCapacity(1).setQuotaCost(1).setMaxAttempts(2)
                    .setReplayWindow(Duration.ofSeconds(30)).setPhysicalCapacity(100)
                    .setPhysicalWindow(Duration.ofSeconds(1));
            var config = new EgonRpcProperties.RpcProviderAdmissionConfig().setEnabled(true).setMaxConcurrent(4)
                    .setMethods(Map.of(RpcReferencePolicy.APPROVED_READ_METHOD, policy));
            RpcAdmissionIdentityResolver identity = (fullMethod, request) -> {
                if (!authenticated.get()) {
                    throw Status.UNAUTHENTICATED.withDescription("sensitive authority details").asRuntimeException();
                }
                return new RpcAdmissionIdentityVO(42, "trusted-client", "https://resource.example/rpc");
            };
            RpcProviderAdmissionComponent admission = new RpcProviderAdmissionComponent(identity, request -> {
                physicalRequests.add(request);
                return new RateLimitDecision(true, 99, Duration.ZERO);
            }, this::logicalDecision, config);
            Descriptors.MethodDescriptor proto = membershipProtoMethod();
            method = MethodDescriptor.<Message, Message>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(RpcReferencePolicy.APPROVED_READ_METHOD)
                    .setRequestMarshaller(ProtoUtils.marshaller((Message) StringValue.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller((Message) StringValue.getDefaultInstance()))
                    .setSchemaDescriptor(new MembershipMethodSupplier(proto)).build();
            var descriptor = new RpcMethodDescriptor(MembershipProvider.class.getMethod("getTenantMembership", StringValue.class),
                    "GetTenantMembership", RpcReferencePolicy.APPROVED_READ_METHOD, true, method, proto);
            var contract = new RpcContractDescriptor(MembershipProvider.class, proto.getService().getFullName(),
                    "test", "1.0.0", List.of(descriptor));
            var binding = new RpcProviderBinding(provider, contract);
            var registry = new RpcProviderMethodRegistry(List.of(binding));
            var availability = new RpcProviderAvailabilityRegistry();
            availability.available(binding.serviceIdentity());
            var definitions = new RpcServerServiceDefinitionFactory(availability,
                    List.of(new RpcAccessGuardExceptionMapper()), admission);
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                    .intercept(new RpcProviderServerInterceptor())
                    .addService(definitions.create(registry).getFirst()).build().start();
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                    .usePlaintext().disableRetry().build();
        }

        private Message invoke(String logicalCallId, int attempt, String request, String claimedDigest) {
            var process = new RpcProcessIdentity("header-source-app", "test", "default", "127.0.0.1", 1L, "fixture");
            var metadata = RpcConsumerClientInterceptor.forTarget(method.getServiceName(), "test", "1.0.0",
                    process, logicalCallId, attempt, claimedDigest);
            return ClientCalls.blockingUnaryCall(ClientInterceptors.intercept(channel, metadata), method,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), StringValue.of(request));
        }

        /** State is a test double; Step 7 owns real Redis atomicity, expiry and Cluster proof. */
        private synchronized LogicalQuotaDecisionVO logicalDecision(LogicalQuotaAcquireCommand command) {
            logicalRequests.add(command);
            if (storeUnavailable.get()) {
                throw new StoreOperationException("sensitive Redis details");
            }
            String scope = keys.logicalQuotaState(command);
            String key = keys.logicalQuotaDecision(command);
            long remaining = remainingByScope.getOrDefault(scope, command.capacity());
            LogicalQuotaAcquireCommand previous = admitted.get(key);
            if (previous != null) {
                if (!previous.requestDigest().equals(command.requestDigest())) {
                    return new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.CONFLICT, remaining, 0);
                }
                if (command.attempt() <= previous.attempt() || command.attempt() > command.maxAttempts()) {
                    return new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.ATTEMPT_REJECTED, remaining, 0);
                }
                admitted.put(key, command);
                return new LogicalQuotaDecisionVO(true, LogicalQuotaDecisionVO.Reason.REPLAY_ALLOWED, remaining, command.attempt());
            }
            if (remaining < command.cost()) {
                return new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.EXHAUSTED, remaining, 0);
            }
            admitted.put(key, command);
            remainingByScope.put(scope, remaining - command.cost());
            logicalDebits.incrementAndGet();
            return new LogicalQuotaDecisionVO(true, LogicalQuotaDecisionVO.Reason.ALLOWED,
                    remaining - command.cost(), command.attempt());
        }

        @Override
        public void close() throws InterruptedException {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Descriptors.MethodDescriptor membershipProtoMethod() throws Descriptors.DescriptorValidationException {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("stage8_identity_fixture.proto")
                .setPackage("egon.tianquan.shoubing.v1").addDependency("google/protobuf/wrappers.proto")
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("IdentityDirectoryService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder().setName("GetTenantMembership")
                                .setInputType(".google.protobuf.StringValue").setOutputType(".google.protobuf.StringValue")))
                .build();
        return Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[]{WrappersProto.getDescriptor()})
                .findServiceByName("IdentityDirectoryService").findMethodByName("GetTenantMembership");
    }

    private record MembershipMethodSupplier(Descriptors.MethodDescriptor method) implements ProtoMethodDescriptorSupplier {
        @Override public Descriptors.MethodDescriptor getMethodDescriptor() { return method; }
        @Override public Descriptors.ServiceDescriptor getServiceDescriptor() { return method.getService(); }
        @Override public Descriptors.FileDescriptor getFileDescriptor() { return method.getFile(); }
    }

    private static final class MembershipProvider {
        private final AtomicInteger calls = new AtomicInteger();
        public StringValue getTenantMembership(StringValue request) {
            calls.incrementAndGet();
            return StringValue.of("provider:" + request.getValue());
        }
    }

    private void assertRateLimitFailure(StatusRuntimeException failure) {
        assertThat(failure.getStatus().getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(failure.getTrailers()).isNotNull();
        assertThat(RpcFailureStage.from(failure.getTrailers()))
                .contains(RpcFailureStage.PROVIDER);
        assertThat(failure.getTrailers().get(RpcMetadataKeys.ERROR_TYPE))
                .isEqualTo("rate-limit");
    }

    @Configuration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        GuardedProvider guardedProvider() {
            return new GuardedProvider();
        }
    }

    @EgonRpcService(
            grpcClass = UnaryFixtureGrpc.class,
            group = "test",
            version = "1.0.0")
    interface EchoContract {

        @EgonRpcMethod(name = "Echo")
        StringValue echo(StringValue request);
    }

    @EgonRpcProvider
    static class GuardedProvider implements EchoContract {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        @RateLimitGuard("rpc-create")
        public StringValue echo(StringValue request) {
            calls.incrementAndGet();
            return StringValue.of("provider:" + request.getValue());
        }

        int calls() {
            return calls.get();
        }
    }
}
