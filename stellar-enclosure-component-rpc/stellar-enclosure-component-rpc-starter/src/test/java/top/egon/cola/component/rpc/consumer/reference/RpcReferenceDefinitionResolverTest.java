package top.egon.cola.component.rpc.consumer.reference;

import com.google.protobuf.StringValue;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.support.RpcProviderTestFixtures.EchoContract;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpcReferenceDefinitionResolverTest {
    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
    private static final String FULL_METHOD = "egon.rpc.fixture.v1.UnaryFixtureService/Echo";
    private final RpcContractDescriptor descriptor = new RpcContractValidator(VALIDATION_UTILS).validate(EchoContract.class);

    @Test
    void declaresOnlyADeploymentBindingForTransport() {
        assertThat(EgonRpcReference.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .contains("bindingId")
                .doesNotContain("mode", "bizCode", "appCode", "env", "loadBalance", "fallbackBean");
    }

    @Test
    void resolvesTrustedServiceAndNarrowsDeadlineWithoutChangingNativeIdentity() throws Exception {
        var definition = new RpcReferenceDefinitionResolver(properties())
                .resolve(Holder.class.getDeclaredField("configured"), descriptor);
        assertThat(definition.target().bindingId()).isEqualTo("identity-directory");
        assertThat(definition.target().dnsTarget()).isEqualTo("dns:///identity-directory.identity-prod.svc.cluster.local:9090");
        assertThat(definition.serviceIdentity()).isEqualTo(RpcServiceIdentity.from(descriptor));
        var policy = definition.policyFor(EchoContract.class.getMethod("echo", StringValue.class));
        assertThat(policy.timeoutMs()).isEqualTo(400);
        assertThat(policy.retries()).isZero();
        var properties = properties();
        properties.getConsumer().setDefaultTimeoutMs(200);
        assertThat(new RpcReferenceDefinitionResolver(properties)
                .resolve(Holder.class.getDeclaredField("configured"), descriptor)
                .policyFor(EchoContract.class.getMethod("echo", StringValue.class)).timeoutMs()).isEqualTo(200);
    }

    @Test
    void freezesMutableDeploymentBindingsAtResolution() throws Exception {
        var properties = properties();
        var binding = properties.getConsumer().getBindings().get("identity-directory");
        var definition = new RpcReferenceDefinitionResolver(properties)
                .resolve(Holder.class.getDeclaredField("configured"), descriptor);
        binding.setService("changed-after-injection");
        binding.setVersion(2);
        binding.getMethods().clear();
        properties.getConsumer().setDefaultTimeoutMs(9000);
        assertThat(definition.target().service()).isEqualTo("identity-directory");
        assertThat(definition.target().version()).isEqualTo(1);
        assertThat(definition.target().methods()).containsExactly(FULL_METHOD);
        assertThatThrownBy(() -> definition.target().methods().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesEvenUnreferencedBindingsAtStartupAndFreezesTheTimeoutCeiling() {
        var properties = properties();
        var resolver = new RpcReferenceDefinitionResolver(properties);
        resolver.initialize();
        properties.getConsumer().setDefaultTimeoutMs(9000);
        assertThat(resolver.defaultTimeoutMs()).isEqualTo(1000);

        var invalid = properties();
        var unused = new EgonRpcProperties.RpcServiceBindingConfig();
        unused.setNamespace("prod");
        unused.setService("127.0.0.1");
        unused.setPort(9090);
        unused.setVersion(1);
        unused.setMethods(Set.of(FULL_METHOD));
        invalid.getConsumer().getBindings().put("unused-invalid", unused);
        assertInvalid(() -> new RpcReferenceDefinitionResolver(invalid).initialize());
    }

    @Test
    void rejectsMissingUnknownUnresolvedBindingsAndLogicalIdentityOverrides() {
        for (String field : Set.of("missing", "unknown", "placeholder", "wrongGroup", "wrongVersion", "invalidTimeout", "retrying")) {
            assertInvalid(() -> new RpcReferenceDefinitionResolver(properties())
                    .resolve(Holder.class.getDeclaredField(field), descriptor));
        }
    }

    @Test
    void resolvesOnlyBindingIdFromTheApplicationEnvironment() throws Exception {
        var environment = new MockEnvironment().withProperty("rpc.identity.binding", "identity-directory");
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        var definition = new RpcReferenceDefinitionResolver(properties(), context)
                .resolve(Holder.class.getDeclaredField("placeholder"), descriptor);
        environment.setProperty("rpc.identity.binding", "another-service");
        assertThat(definition.target().bindingId()).isEqualTo("identity-directory");
        assertInvalid(() -> new RpcReferenceDefinitionResolver(properties(), context)
                .resolve("${rpc.identity.binding}", RpcServiceIdentity.from(descriptor), Set.of(FULL_METHOD)));
    }

    @Test
    void requiresEveryTypedMethodToBeApprovedByTheBinding() {
        var properties = properties();
        properties.getConsumer().getBindings().get("identity-directory")
                .setMethods(Set.of("egon.rpc.fixture.v1.UnaryFixtureService/Other"));
        assertInvalid(() -> new RpcReferenceDefinitionResolver(properties)
                .resolve(Holder.class.getDeclaredField("configured"), descriptor));
    }

    @Test
    void resolvesGenericMethodsThroughTheSameTrustedBinding() {
        var resolver = new RpcReferenceDefinitionResolver(properties());
        var identity = RpcServiceIdentity.from(descriptor);
        assertThat(resolver.resolve("identity-directory", identity, Set.of(FULL_METHOD)).typedPolicies()).isEmpty();
        assertInvalid(() -> resolver.resolve("identity-directory", identity, Set.of("another.v1.Service/Echo")));
        assertInvalid(() -> resolver.resolve("identity-directory", identity, Set.of("egon.rpc.fixture.v1.UnaryFixtureService/Other")));
    }

    @Test
    void rejectsAddressesVersionBackendsAndMalformedNativeMethods() {
        for (String service : Set.of("127.0.0.1", "10-0-0-1:30080", "https://identity", "identity.prod", "identity-stable",
                "identity-canary", "stable", "canary", "Identity", "identity_name")) {
            assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod", service, 9090, Set.of(FULL_METHOD)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String method : Set.of("/" + FULL_METHOD, "*", "service/*", "service/method/extra", "service/", "service/Method?x=1")) {
            assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod", "identity", 9090, Set.of(method)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int port : new int[]{0, -1, 65536, 19099}) {
            assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod", "identity", port, Set.of(FULL_METHOD)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 0, "prod", "identity", 9090, Set.of(FULL_METHOD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod.invalid", "identity", 9090, Set.of(FULL_METHOD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod", "identity", 9090, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RpcServiceTargetVO("identity", 1, "prod", "identity", 9090,
                Set.of(FULL_METHOD, "another.v1.Service/Echo"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static EgonRpcProperties properties() {
        var properties = new EgonRpcProperties();
        properties.getConsumer().setDefaultTimeoutMs(1000);
        var binding = new EgonRpcProperties.RpcServiceBindingConfig();
        binding.setNamespace("identity-prod");
        binding.setService("identity-directory");
        binding.setPort(9090);
        binding.setVersion(1);
        binding.setMethods(new LinkedHashSet<>(Set.of(FULL_METHOD)));
        properties.getConsumer().getBindings().put("identity-directory", binding);
        return properties;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(EgonRpcException.class,
                error -> assertThat(error.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_INVALID_CONTRACT));
    }

    static final class Holder {
        @EgonRpcReference(bindingId = "identity-directory", timeoutMs = 400, group = "test", version = "1.0.0")
        private EchoContract configured;
        @EgonRpcReference(bindingId = "${rpc.identity.binding}") private EchoContract placeholder;
        @EgonRpcReference private EchoContract missing;
        @EgonRpcReference(bindingId = "unknown") private EchoContract unknown;
        @EgonRpcReference(bindingId = "identity-directory", group = "other") private EchoContract wrongGroup;
        @EgonRpcReference(bindingId = "identity-directory", version = "2.0.0") private EchoContract wrongVersion;
        @EgonRpcReference(bindingId = "identity-directory", timeoutMs = 0) private EchoContract invalidTimeout;
        @EgonRpcReference(bindingId = "identity-directory", retries = 1) private EchoContract retrying;
    }
}
