package top.egon.cola.component.rpc.test.consumer;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelKey;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelFactory;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.proxy.EgonRpcReferenceBeanPostProcessor;
import top.egon.cola.component.rpc.consumer.proxy.RpcConsumerProxyFactory;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerInterceptor;
import top.egon.cola.component.rpc.test.contract.EchoRpc;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;
import top.egon.cola.component.rpc.test.contract.proto.EchoServiceGrpc;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EchoRpcClientContextTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final ValidationUtils VALIDATION_UTILS = new ValidationUtils(VALIDATOR_FACTORY.getValidator());
    private static final String FULL_METHOD = "egon.rpc.test.v1.EchoService/Echo";

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldInjectTheStableServiceProxyAndCallNativeH2c() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RpcInvocationMetadata> received = new AtomicReference<>();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .intercept(new RpcProviderServerInterceptor())
                .addService(new EchoServiceGrpc.EchoServiceImplBase() {
                    @Override
                    public void echo(EchoRequest request, StreamObserver<EchoResponse> observer) {
                        calls.incrementAndGet();
                        RpcInvocationMetadata metadata = RpcInvocationMetadata.current();
                        received.set(metadata);
                        observer.onNext(EchoResponse.newBuilder().setProviderId("local-provider")
                                .setMessage(request.getMessage()).setInvocationId(metadata.logicalCallId())
                                .setTraceId(metadata.traceId()).build());
                        observer.onCompleted();
                    }
                }).build().start();
        AtomicReference<ManagedChannel> opened = new AtomicReference<>();
        List<RpcChannelKey> channelKeys = new CopyOnWriteArrayList<>();
        RpcConsumerChannelFactory channels = mock(RpcConsumerChannelFactory.class);
        // Only the isolated test maps the validated Service key to loopback; no DNS/Cilium claim is made.
        when(channels.create(any(RpcChannelKey.class))).thenAnswer(invocation -> {
            channelKeys.add(invocation.getArgument(0));
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                    .usePlaintext().disableServiceConfigLookUp().disableRetry().build();
            opened.set(channel);
            return channel;
        });
        try (var pool = new RpcConsumerChannelPool(channels, Duration.ofSeconds(1));
             var strategies = new RpcReferenceStrategyFactory(pool);
             var executor = new RpcInvocationExecutor(new RpcStatusExceptionMapper())) {
            RpcConsumerProxyFactory proxies = new RpcConsumerProxyFactory(executor,
                    new RpcProcessIdentity("consumer-test", "test", "default", "127.0.0.1", 1L, "consumer-1"),
                    List.of());
            EchoRpcClient client = new EchoRpcClient();
            new EgonRpcReferenceBeanPostProcessor(new RpcContractValidator(VALIDATION_UTILS),
                    new RpcReferenceDefinitionResolver(properties()), strategies, proxies)
                    .postProcessBeforeInitialization(client, "echoRpcClient");

            assertThat(client.rpcProxy()).isNotNull().isInstanceOf(EchoRpc.class);
            EchoResponse response = client.echo("hello");
            assertThat(response.getProviderId()).isEqualTo("local-provider");
            assertThat(response.getMessage()).isEqualTo("hello");
            assertThat(response.getInvocationId()).isNotBlank().isEqualTo(received.get().logicalCallId());
            assertThat(response.getTraceId()).isNotBlank();
            assertThat(received.get().attempt()).isEqualTo(1);
            assertThat(received.get().requestDigest()).isEqualTo(
                    RpcInvocationMetadata.canonicalRequestDigest(EchoRequest.newBuilder().setMessage("hello").build()));
            assertThat(calls).hasValue(1);
            assertThat(channelKeys).singleElement().satisfies(key -> assertThat(key.dnsTarget())
                    .isEqualTo("dns:///rpc-test-provider.rpc-tests.svc.cluster.local:19090"));
        } finally {
            if (opened.get() != null) {
                opened.get().shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingDeploymentBindingFailsBeforeOpeningAChannel() {
        RpcReferenceStrategyFactory strategies = mock(RpcReferenceStrategyFactory.class);
        EgonRpcReferenceBeanPostProcessor processor = new EgonRpcReferenceBeanPostProcessor(
                new RpcContractValidator(VALIDATION_UTILS), new RpcReferenceDefinitionResolver(new EgonRpcProperties()),
                strategies, mock(RpcConsumerProxyFactory.class));

        assertThatThrownBy(() -> processor.postProcessBeforeInitialization(new EchoRpcClient(), "echoRpcClient"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("binding is not configured");
        verifyNoInteractions(strategies);
    }

    @Test
    void applicationYamlEnablesOnlyTheNativeConsumerAndBindsTheExactMethod() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("rpc-consumer-demo", resource)
                .forEach(source -> environment.getPropertySources().addLast(source));
        EgonRpcProperties configured = Binder.get(environment)
                .bind("egon.cola.component.rpc", Bindable.of(EgonRpcProperties.class))
                .orElseThrow(() -> new IllegalStateException("RPC demo configuration was not bound"));

        assertThat(VALIDATOR_FACTORY.getValidator().validate(configured)).isEmpty();
        assertThat(configured.isEnabled()).isTrue();
        assertThat(configured.getTransport()).isEqualTo("h2c");
        assertThat(configured.getConsumer().isEnabled()).isTrue();
        assertThat(configured.getProvider().isEnabled()).isFalse();
        assertThat(configured.getConsumer().getBindings()).containsOnlyKeys("echo-service");
        assertThat(configured.getConsumer().getBindings().get("echo-service").getMethods()).containsExactly(FULL_METHOD);
        assertThat(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("tianshu:", "registry:", "tls:", "certificate", "gateway:");
    }

    private static EgonRpcProperties properties() {
        EgonRpcProperties configured = new EgonRpcProperties();
        configured.getConsumer().getBindings().put("echo-service", new EgonRpcProperties.RpcServiceBindingConfig()
                .setVersion(1).setNamespace("rpc-tests").setService("rpc-test-provider").setPort(19090)
                .setMethods(Set.of(FULL_METHOD)));
        return configured;
    }
}
