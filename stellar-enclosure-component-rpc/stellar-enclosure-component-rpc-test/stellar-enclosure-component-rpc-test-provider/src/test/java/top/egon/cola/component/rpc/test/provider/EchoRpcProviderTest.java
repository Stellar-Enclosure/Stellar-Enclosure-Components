package top.egon.cola.component.rpc.test.provider;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.interceptor.RpcConsumerClientInterceptor;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBeanScanner;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerFactory;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerInterceptor;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;
import top.egon.cola.component.rpc.test.contract.proto.EchoRequest;
import top.egon.cola.component.rpc.test.contract.proto.EchoResponse;
import top.egon.cola.component.rpc.test.contract.proto.EchoServiceGrpc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EchoRpcProviderTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final ValidationUtils VALIDATION_UTILS = new ValidationUtils(VALIDATOR_FACTORY.getValidator());

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldReturnProtobufResponseAndBeDiscoveredAsProvider() {
        EchoRpcProvider provider = new EchoRpcProvider("provider-a");
        EchoResponse response = provider.echo(EchoRequest.newBuilder()
                .setMessage("hello")
                .build());

        assertThat(response.getProviderId()).isEqualTo("provider-a");
        assertThat(response.getMessage()).isEqualTo("hello");

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    EchoRpcProvider.class,
                    () -> provider
            );
            context.refresh();

            assertThat(new RpcProviderBeanScanner(
                    context,
                    new RpcContractValidator(VALIDATION_UTILS)
            ).scan().providers()).hasSize(1);
        }
    }

    @Test
    void discoveredProviderExportsTheNativeProtobufMethodOverLocalH2c() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("echoRpcProvider", EchoRpcProvider.class, () -> new EchoRpcProvider("provider-tcp"));
            context.refresh();
            var registry = new RpcProviderBeanScanner(context, new RpcContractValidator(VALIDATION_UTILS)).scan();
            var availability = new RpcProviderAvailabilityRegistry();
            registry.providers().forEach(binding -> availability.available(binding.serviceIdentity()));
            Server server = new RpcProviderServerFactory().create("127.0.0.1", 0,
                    new RpcServerServiceDefinitionFactory(availability).create(registry),
                    List.of(new RpcProviderServerInterceptor())).start();
            ManagedChannel channel = null;
            try {
                channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext()
                        .disableServiceConfigLookUp().disableRetry().build();
                EchoRequest request = EchoRequest.newBuilder().setMessage("native-hello").build();
                var metadata = RpcConsumerClientInterceptor.forTarget(EchoServiceGrpc.SERVICE_NAME, "default", "1.0.0",
                        new RpcProcessIdentity("provider-test-client", "test", "default", "127.0.0.1", 1L, "fixture"),
                        "native-provider-call", 1, RpcInvocationMetadata.canonicalRequestDigest(request));
                EchoResponse response = EchoServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, metadata))
                        .withDeadlineAfter(3, TimeUnit.SECONDS).echo(request);

                assertThat(response.getProviderId()).isEqualTo("provider-tcp");
                assertThat(response.getMessage()).isEqualTo("native-hello");
                assertThat(response.getInvocationId()).isEqualTo("native-provider-call");
                assertThat(response.getTraceId()).isNotBlank();
            } finally {
                if (channel != null) {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void applicationYamlEnablesOnlyTheNativeProviderListener() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("rpc-provider-demo", resource)
                .forEach(source -> environment.getPropertySources().addLast(source));
        EgonRpcProperties configured = Binder.get(environment)
                .bind("egon.cola.component.rpc", Bindable.of(EgonRpcProperties.class))
                .orElseThrow(() -> new IllegalStateException("RPC demo configuration was not bound"));

        assertThat(VALIDATOR_FACTORY.getValidator().validate(configured)).isEmpty();
        assertThat(configured.isEnabled()).isTrue();
        assertThat(configured.getTransport()).isEqualTo("h2c");
        assertThat(configured.getProvider().isEnabled()).isTrue();
        assertThat(configured.getConsumer().isEnabled()).isFalse();
        assertThat(configured.getProvider().getBindAddress()).isNotBlank();
        assertThat(configured.getProvider().getPort()).isBetween(1, 65535);
        assertThat(resource.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("tianshu:", "registry:", "tls:", "certificate", "gateway:");
    }
}
