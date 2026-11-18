package top.egon.cola.component.rpc.test.contract;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.grpc.stub.MetadataUtils;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelKey;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelFactory;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericTargetCache;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInterceptorFactory;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.proxy.EgonRpcReferenceBeanPostProcessor;
import top.egon.cola.component.rpc.consumer.proxy.RpcConsumerProxyFactory;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.descriptor.RpcMethodDescriptor;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBinding;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerFactory;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerInterceptor;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;
import top.egon.cola.component.rpc.test.contract.proto.EchoServiceGrpc;
import top.egon.cola.component.rpc.test.fixture.consumer.AsyncEchoRpcTestClient;
import top.egon.cola.component.rpc.test.fixture.consumer.EchoRpcTestClient;
import top.egon.cola.component.rpc.test.fixture.provider.AsyncEchoRpcTestProvider;
import top.egon.cola.component.rpc.test.fixture.provider.EchoRpcTestProvider;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native local TCP contracts; Cilium stream distribution and network isolation remain platform acceptance checks. */
@Timeout(20)
public class RpcServiceTcpContractTest {

    private static final String SERVICE = "egon.rpc.test.v1.EchoService";
    private static final String METHOD = SERVICE + "/Echo";
    private static final String BINDING = "echo-service";
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final RpcContractValidator CONTRACTS = new RpcContractValidator(
            new ValidationUtils(VALIDATORS.getValidator()));

    @BeforeAll
    static void initializeLogicalCallIds() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Test
    void consumerApplicationResolvesItsStableServiceBindingWithoutDirectory() throws Exception {
        var environment = new StandardEnvironment();
        var resource = new FileSystemResource(Path.of("../stellar-enclosure-component-rpc-test-consumer",
                "src/main/resources/application.yml"));
        new YamlPropertySourceLoader().load("consumer", resource)
                .forEach(source -> environment.getPropertySources().addLast(source));
        var properties = Binder.get(environment).bind("egon.cola.component.rpc", EgonRpcProperties.class)
                .orElseGet(EgonRpcProperties::new);
        var definition = new RpcReferenceDefinitionResolver(properties).resolve(BINDING,
                new RpcServiceIdentity(SERVICE, "default", "1.0.0"), Set.of(METHOD));

        assertThat(definition.target().bindingId()).isEqualTo(BINDING);
        assertThat(definition.target().dnsTarget()).startsWith("dns:///").contains(".svc.cluster.local:");
        assertThat(definition.target().methods()).containsExactly(METHOD);
    }

    @Test
    void typedAndGenericCallsUseGeneratedProtobufOverOneCleartextTcpConnection() throws Exception {
        try (TcpRuntime runtime = echoRuntime(new EchoRpcTestProvider("tcp-provider"), EchoRpc.class)) {
            EchoRpcTestClient typed = runtime.inject(new EchoRpcTestClient());
            assertEcho(typed.echo("typed"), "typed");
            assertEcho(EchoResponse.parseFrom(runtime.generic.invokeBlocking(invocation("generic", 3000))), "generic");
            assertEcho(EchoResponse.parseFrom(runtime.generic.invokeAsync(invocation("async-generic", 3000))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)), "async-generic");
            assertEcho(typed.echo("reused"), "reused");

            assertThat(runtime.channels).hasSize(1);
            assertThat(runtime.probe.arrivals).hasSize(4).allSatisfy(arrival -> {
                assertThat(arrival.fullMethod()).isEqualTo(METHOD);
                assertThat(arrival.attempt()).isEqualTo("1");
                assertThat(arrival.logicalCallId()).isNotBlank();
                assertThat(arrival.requestDigest()).matches("[a-f0-9]{64}");
                assertThat(arrival.deadlineMillis()).isBetween(1L, 3000L);
                assertThat(arrival.tls()).isFalse();
                assertThat(arrival.remote()).isInstanceOfSatisfying(InetSocketAddress.class,
                        address -> assertThat(address.getAddress().isLoopbackAddress()).isTrue());
            });
            assertThat(runtime.probe.arrivals).extracting(ArrivalVO::logicalCallId).doesNotHaveDuplicates();
            assertThat(runtime.probe.arrivals).extracting(ArrivalVO::remote)
                    .containsOnly(runtime.probe.arrivals.getFirst().remote());
        }
    }

    @Test
    void unavailableWithoutAnApprovalHasOneServerArrivalAndNoImplicitBusinessAttempt() throws Exception {
        var rejection = new RejectUnavailableInterceptor(Integer.MAX_VALUE);
        try (TcpRuntime runtime = new TcpRuntime(new EchoRpcTestProvider("unused"),
                CONTRACTS.validate(EchoRpc.class), properties(METHOD, false), List.of(), rejection)) {
            var client = runtime.inject(new EchoRpcTestClient());
            assertThatThrownBy(() -> client.echo("single"))
                    .isInstanceOfSatisfying(EgonRpcException.class,
                            failure -> assertThat(failure.getRpcErrorCode())
                                    .isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE));
            assertThat(rejection.calls).hasValue(1);
            assertThat(runtime.probe.arrivals).hasSize(1);
            assertThat(runtime.probe.arrivals.getFirst().attempt()).isEqualTo("1");
        }
    }

    @Test
    void oneApprovedReplayKeepsOneLogicalIdDigestAndStableServiceConnection() throws Exception {
        RpcContractDescriptor contract = membershipContract();
        var rejection = new RejectUnavailableInterceptor(1);
        try (TcpRuntime runtime = new TcpRuntime(new MembershipProvider(), contract,
                properties(RpcReferencePolicy.APPROVED_READ_METHOD, true), List.of(), rejection)) {
            MembershipRpc client = runtime.membership(contract, "approved");
            assertThat(client.membership(request("membership")).getMessage()).isEqualTo("membership");
            assertThat(rejection.calls).hasValue(2);
            assertThat(runtime.probe.arrivals).hasSize(2);
            assertThat(runtime.probe.arrivals).extracting(ArrivalVO::attempt).containsExactly("1", "2");
            ArrivalVO first = runtime.probe.arrivals.getFirst();
            ArrivalVO second = runtime.probe.arrivals.getLast();
            assertThat(second.logicalCallId()).isEqualTo(first.logicalCallId());
            assertThat(second.requestDigest()).isEqualTo(first.requestDigest())
                    .isEqualTo(RpcInvocationMetadata.canonicalRequestDigest(request("membership")));
            assertThat(second.deadlineMillis()).isBetween(1L, 600L);
            assertThat(second.remote()).isEqualTo(first.remote());
            assertThat(runtime.channels).hasSize(1);
        }
    }

    @Test
    void approvalStillCapsTheWireAtTwoArrivalsAndAReferenceCanOptOut() throws Exception {
        RpcContractDescriptor contract = membershipContract();
        var rejection = new RejectUnavailableInterceptor(Integer.MAX_VALUE);
        try (TcpRuntime runtime = new TcpRuntime(new MembershipProvider(), contract,
                properties(RpcReferencePolicy.APPROVED_READ_METHOD, true), List.of(), rejection)) {
            var approved = runtime.membership(contract, "approved");
            assertThatThrownBy(() -> approved.membership(request("exhaust")))
                    .isInstanceOfSatisfying(EgonRpcException.class,
                            failure -> assertThat(failure.getRpcErrorCode())
                                    .isEqualTo(EgonRpcErrorCode.RPC_PROVIDER_UNAVAILABLE));
            assertThat(rejection.calls).hasValue(2);
            assertThat(runtime.probe.arrivals).extracting(ArrivalVO::attempt).containsExactly("1", "2");
            String firstId = runtime.probe.arrivals.getFirst().logicalCallId();

            var optedOut = runtime.membership(contract, "optedOut");
            assertThatThrownBy(() -> optedOut.membership(request("one"))).isInstanceOf(EgonRpcException.class);
            assertThat(rejection.calls).hasValue(3);
            assertThat(runtime.probe.arrivals.getLast().attempt()).isEqualTo("1");
            assertThat(runtime.probe.arrivals.getLast().logicalCallId()).isNotEqualTo(firstId);
        }
    }

    @Test
    void callerDeadlineReachesTheAsyncProviderAndCancelsItsPendingSource() throws Exception {
        var provider = new AsyncEchoRpcTestProvider();
        try (TcpRuntime runtime = echoRuntime(provider, AsyncEchoRpc.class);
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var client = runtime.inject(new AsyncEchoRpcTestClient());
            runtime.awaitReady();
            Context.CancellableContext incoming = Context.current().withDeadlineAfter(500, TimeUnit.MILLISECONDS, timer);
            try {
                var pending = incoming.call(() -> client.echoAsync("deadline")).toCompletableFuture();
                assertThat(runtime.probe.started.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class,
                                failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(EgonRpcException.class,
                                        error -> assertThat(error.getRpcErrorCode())
                                                .isEqualTo(EgonRpcErrorCode.RPC_DEADLINE_EXCEEDED)));
                assertThat(runtime.probe.cancelled.await(3, TimeUnit.SECONDS)).isTrue();
                await(provider::pendingCancelled);
                assertThat(provider.calls()).isEqualTo(1);
                assertThat(runtime.probe.arrivals).hasSize(1);
                assertThat(runtime.probe.arrivals.getFirst().deadlineMillis()).isBetween(1L, 500L);
            } finally {
                incoming.cancel(null);
            }
        }
    }

    @Test
    void cancellingTheAsyncClientCancelsTheTransportAndProviderWithoutReplay() throws Exception {
        var provider = new AsyncEchoRpcTestProvider();
        try (TcpRuntime runtime = echoRuntime(provider, AsyncEchoRpc.class)) {
            var client = runtime.inject(new AsyncEchoRpcTestClient());
            var pending = client.echoAsync("cancel").toCompletableFuture();
            assertThat(runtime.probe.started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.cancel(true)).isTrue();
            assertThat(runtime.probe.cancelled.await(3, TimeUnit.SECONDS)).isTrue();
            await(provider::pendingCancelled);
            assertThat(pending.isCancelled()).isTrue();
            assertThat(provider.calls()).isEqualTo(1);
            assertThat(runtime.probe.arrivals).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-envoy-retry-on", "x-envoy-max-retries", "x-envoy-upstream-rq-timeout-ms",
            "x-envoy-hedge-on-per-try-timeout", "x-egon-rpc-invocation-id"})
    void typedAndGenericExtensionsCannotSupplyRetryTimeoutOrFrameworkIdentityMetadata(String header) throws Exception {
        Metadata disallowed = new Metadata();
        disallowed.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER), "forbidden");
        RpcClientInterceptorFactory extension = ignored -> MetadataUtils.newAttachHeadersInterceptor(disallowed);
        try (TcpRuntime runtime = new TcpRuntime(new EchoRpcTestProvider("unused"),
                CONTRACTS.validate(EchoRpc.class), properties(METHOD, false), List.of(extension), null)) {
            var typed = runtime.inject(new EchoRpcTestClient());
            assertThatThrownBy(() -> typed.echo("blocked")).isInstanceOf(EgonRpcException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.generic.invokeBlocking(invocation("blocked", 3000)))
                    .isInstanceOf(EgonRpcException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.probe.arrivals).isEmpty();
        }
    }

    @Test
    void nativeClientsCannotBypassTheProviderMetadataGuard() throws Exception {
        try (TcpRuntime runtime = echoRuntime(new EchoRpcTestProvider("tcp-provider"), EchoRpc.class)) {
            var typed = runtime.inject(new EchoRpcTestClient());
            assertEcho(typed.echo("warm"), "warm");
            Metadata bad = new Metadata();
            bad.put(Metadata.Key.of("x-envoy-max-retries", Metadata.ASCII_STRING_MARSHALLER), "100");
            var nativeClient = EchoServiceGrpc.newBlockingStub(runtime.channels.getFirst())
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(bad))
                    .withDeadlineAfter(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> nativeClient.echo(request("reject")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }
    }

    private static TcpRuntime echoRuntime(Object provider, Class<?> contract) throws Exception {
        return new TcpRuntime(provider, CONTRACTS.validate(contract), properties(METHOD, false), List.of(), null);
    }

    private static EgonRpcProperties properties(String method, boolean retry) {
        var properties = new EgonRpcProperties();
        properties.getConsumer().setDefaultTimeoutMs(3000).getBindings().put(BINDING,
                new EgonRpcProperties.RpcServiceBindingConfig().setVersion(1).setNamespace("rpc-test")
                        .setService("echo-rpc").setPort(18122).setMethods(Set.of(method)));
        if (retry) {
            properties.getConsumer().getRetryApprovals().put(method,
                    new EgonRpcProperties.RpcRetryApprovalConfig(1, 600, 1500));
        }
        return properties;
    }

    private static EchoRequest request(String message) {
        return EchoRequest.newBuilder().setMessage(message).build();
    }

    private static RpcGenericInvocation invocation(String message, long timeoutMs) {
        return new RpcGenericInvocation(BINDING, SERVICE, "default", "1.0.0", METHOD,
                request(message).toByteArray(), timeoutMs);
    }

    private static void assertEcho(EchoResponse response, String message) {
        assertThat(response.getMessage()).isEqualTo(message);
        assertThat(response.getProviderId()).isEqualTo("tcp-provider");
        assertThat(response.getInvocationId()).isNotBlank();
        assertThat(response.getTraceId()).isNotBlank();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** Exact approved method name with generated Echo messages; no production identity-directory schema is redefined. */
    private static RpcContractDescriptor membershipContract() throws Exception {
        String service = "egon.tianquan.shoubing.v1.IdentityDirectoryService";
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("tcp_retry_fixture.proto")
                .setPackage("egon.tianquan.shoubing.v1")
                .addDependency(EchoRequest.getDescriptor().getFile().getName())
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("IdentityDirectoryService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder().setName("GetTenantMembership")
                                .setInputType(".egon.rpc.test.v1.EchoRequest")
                                .setOutputType(".egon.rpc.test.v1.EchoResponse"))).build();
        var proto = Descriptors.FileDescriptor.buildFrom(file,
                new Descriptors.FileDescriptor[]{EchoRequest.getDescriptor().getFile()})
                .findServiceByName("IdentityDirectoryService").findMethodByName("GetTenantMembership");
        @SuppressWarnings("unchecked")
        var grpc = (MethodDescriptor<Message, Message>) (MethodDescriptor<?, ?>) EchoServiceGrpc.getEchoMethod()
                .toBuilder().setFullMethodName(RpcReferencePolicy.APPROVED_READ_METHOD)
                .setSchemaDescriptor(new FixtureMethodSupplierVO(proto)).build();
        var method = new RpcMethodDescriptor(MembershipRpc.class.getMethod("membership", EchoRequest.class),
                "GetTenantMembership", RpcReferencePolicy.APPROVED_READ_METHOD, true, grpc, proto);
        return new RpcContractDescriptor(MembershipRpc.class, service, "default", "1.0.0", List.of(method));
    }

    public interface MembershipRpc {
        @EgonRpcMethod(name = "GetTenantMembership", idempotent = true)
        EchoResponse membership(EchoRequest request);
    }

    private static final class MembershipProvider implements MembershipRpc {
        @Override public EchoResponse membership(EchoRequest request) {
            return EchoResponse.newBuilder().setMessage(request.getMessage()).build();
        }
    }

    private static final class MembershipReferences {
        @EgonRpcReference(bindingId = BINDING) private MembershipRpc approved;
        @EgonRpcReference(bindingId = BINDING, retries = 0) private MembershipRpc optedOut;
    }

    private record FixtureMethodSupplierVO(Descriptors.MethodDescriptor method) implements ProtoMethodDescriptorSupplier {
        @Override public Descriptors.MethodDescriptor getMethodDescriptor() { return method; }
        @Override public Descriptors.ServiceDescriptor getServiceDescriptor() { return method.getService(); }
        @Override public Descriptors.FileDescriptor getFileDescriptor() { return method.getFile(); }
    }

    private record ArrivalVO(String fullMethod, String logicalCallId, String attempt, String requestDigest,
                             long deadlineMillis, SocketAddress remote, boolean tls) { }

    private static final class WireProbe implements ServerInterceptor {
        private final List<ArrivalVO> arrivals = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            arrivals.add(new ArrivalVO(call.getMethodDescriptor().getFullMethodName(),
                    headers.get(RpcMetadataKeys.LOGICAL_CALL_ID), headers.get(RpcMetadataKeys.ATTEMPT),
                    headers.get(RpcMetadataKeys.REQUEST_DIGEST), Context.current().getDeadline() == null ? -1
                            : Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS),
                    call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR),
                    call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION) != null));
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
                @Override public void onHalfClose() { super.onHalfClose(); started.countDown(); }
                @Override public void onCancel() { super.onCancel(); cancelled.countDown(); }
            };
        }
    }

    /** Trailers-only failures model an uncommitted unavailable upstream, not a business exception or quota outage. */
    private static final class RejectUnavailableInterceptor implements ServerInterceptor {
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();
        private RejectUnavailableInterceptor(int failures) { this.failures = failures; }
        @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            if (calls.incrementAndGet() <= failures) {
                call.close(Status.UNAVAILABLE.withDescription("fixture upstream unavailable"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
            return next.startCall(call, headers);
        }
    }

    /** Only the channel-factory test seam maps a validated Service to loopback; all invocation machinery is real. */
    private static final class TcpRuntime implements AutoCloseable {
        private final List<ManagedChannel> channels = new CopyOnWriteArrayList<>();
        private final WireProbe probe = new WireProbe();
        private final RpcInvocationExecutor executor = new RpcInvocationExecutor(new RpcStatusExceptionMapper());
        private final RpcProcessIdentity identity = new RpcProcessIdentity("tcp-contract", "test", "127.0.0.1", 1, "tcp-1");
        private Server server;
        private RpcConsumerChannelPool pool;
        private RpcReferenceStrategyFactory strategies;
        private RpcReferenceDefinitionResolver resolver;
        private RpcConsumerProxyFactory proxies;
        private RpcGenericTargetCache cache;
        private RpcGenericInvoker generic;

        private TcpRuntime(Object provider, RpcContractDescriptor contract, EgonRpcProperties properties,
                           List<RpcClientInterceptorFactory> extensions, ServerInterceptor fault) throws Exception {
            try {
                var binding = new RpcProviderBinding(provider, contract);
                var availability = new RpcProviderAvailabilityRegistry();
                availability.available(binding.serviceIdentity());
                var definitions = new RpcServerServiceDefinitionFactory(availability)
                        .create(new RpcProviderMethodRegistry(List.of(binding)));
                List<ServerInterceptor> interceptors = fault == null
                        ? List.of(probe, new RpcProviderServerInterceptor())
                        : List.of(probe, new RpcProviderServerInterceptor(), fault);
                server = new RpcProviderServerFactory().create("127.0.0.1", 0, definitions, interceptors).start();
                var localTransport = new RpcConsumerChannelFactory(4 * 1024 * 1024, 1000) {
                    @Override public ManagedChannel create(RpcChannelKey key) {
                        assertThat(key.dnsTarget()).isEqualTo("dns:///echo-rpc.rpc-test.svc.cluster.local:18122");
                        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                                .usePlaintext().disableServiceConfigLookUp().disableRetry().build();
                        channels.add(channel);
                        return channel;
                    }
                };
                pool = new RpcConsumerChannelPool(localTransport, Duration.ofSeconds(1));
                strategies = new RpcReferenceStrategyFactory(pool);
                resolver = new RpcReferenceDefinitionResolver(properties);
                proxies = new RpcConsumerProxyFactory(executor, identity, extensions);
                cache = new RpcGenericTargetCache(resolver, strategies, 4, Duration.ofMinutes(1));
                generic = new RpcGenericInvoker(cache, executor, identity, extensions);
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private <T> T inject(T client) {
            new EgonRpcReferenceBeanPostProcessor(CONTRACTS, resolver, strategies, proxies)
                    .postProcessBeforeInitialization(client, "tcpClient");
            return client;
        }

        private MembershipRpc membership(RpcContractDescriptor contract, String field) throws Exception {
            var definition = resolver.resolve(MembershipReferences.class.getDeclaredField(field), contract);
            return proxies.create(contract, definition, strategies.create(definition));
        }

        private void awaitReady() throws InterruptedException {
            ManagedChannel channel = channels.getFirst();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (channel.getState(true) != io.grpc.ConnectivityState.READY) {
                var state = channel.getState(true);
                if (state == io.grpc.ConnectivityState.READY) { return; }
                var changed = new CountDownLatch(1);
                channel.notifyWhenStateChanged(state, changed::countDown);
                assertThat(changed.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)).isTrue();
                assertThat(System.nanoTime()).isLessThan(deadline);
            }
        }

        @Override public void close() throws Exception {
            try {
                executor.close();
                if (cache != null) { cache.close(); }
                if (strategies != null) { strategies.close(); }
                if (pool != null) { pool.close(); }
            } finally {
                for (ManagedChannel channel : channels) {
                    channel.shutdownNow();
                    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }
                if (server != null) {
                    server.shutdownNow();
                    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }
}
