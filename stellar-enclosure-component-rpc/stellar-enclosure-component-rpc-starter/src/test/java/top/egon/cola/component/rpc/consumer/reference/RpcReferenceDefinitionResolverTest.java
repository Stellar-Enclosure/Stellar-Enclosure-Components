package top.egon.cola.component.rpc.consumer.reference;

import com.google.protobuf.StringValue;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.descriptor.RpcMethodDescriptor;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.support.RpcProviderTestFixtures.EchoContract;

import java.util.LinkedHashSet;
import java.util.List;
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
    void appliesOnlyTheDeployedExactReadApprovalAndLetsTheReferenceOptOut() throws Exception {
        var approvedDescriptor = approvedDescriptor();
        var properties = approvedProperties();
        var resolver = new RpcReferenceDefinitionResolver(properties);
        var method = EchoContract.class.getMethod("echo", StringValue.class);

        var inherited = resolver.resolve(Holder.class.getDeclaredField("inherited"), approvedDescriptor).policyFor(method);
        assertThat(inherited).isEqualTo(new RpcReferencePolicy(1500, 1, 600));
        var explicit = resolver.resolve(Holder.class.getDeclaredField("retrying"), approvedDescriptor).policyFor(method);
        assertThat(explicit).isEqualTo(inherited);
        var optedOut = resolver.resolve(Holder.class.getDeclaredField("optedOut"), approvedDescriptor).policyFor(method);
        assertThat(optedOut).isEqualTo(new RpcReferencePolicy(1500, 0, 600));
        var narrowed = resolver.resolve(Holder.class.getDeclaredField("configured"), approvedDescriptor).policyFor(method);
        assertThat(narrowed).isEqualTo(new RpcReferencePolicy(400, 1, 400));
        assertThat(resolver.resolve("identity-directory", RpcServiceIdentity.from(approvedDescriptor),
                Set.of(RpcReferencePolicy.APPROVED_READ_METHOD)).typedPolicies()).isEmpty();
    }

    @Test
    void aReferenceCannotGrantRetryAndAnAbsentApprovalStaysSingleAttempt() throws Exception {
        var properties = approvedProperties();
        properties.getConsumer().getRetryApprovals().clear();
        var resolver = new RpcReferenceDefinitionResolver(properties);
        var approvedDescriptor = approvedDescriptor();
        var method = EchoContract.class.getMethod("echo", StringValue.class);
        assertThat(resolver.resolve(Holder.class.getDeclaredField("inherited"), approvedDescriptor)
                .policyFor(method)).isEqualTo(new RpcReferencePolicy(2000));
        assertInvalid(() -> resolver.resolve(Holder.class.getDeclaredField("retrying"), approvedDescriptor));

        var disabled = approvedProperties();
        disabled.getConsumer().getRetryApprovals().get(RpcReferencePolicy.APPROVED_READ_METHOD).setRetries(0);
        assertInvalid(() -> new RpcReferenceDefinitionResolver(disabled)
                .resolve(Holder.class.getDeclaredField("retrying"), approvedDescriptor));
    }

    @Test
    void explicitMethodOptOutNarrowsTheDeployedGrant() throws Exception {
        var method = MethodOptOutContract.class.getMethod("membership", StringValue.class);
        var definition = new RpcReferenceDefinitionResolver(approvedProperties())
                .resolve(Holder.class.getDeclaredField("methodOptedOut"),
                        approvedDescriptor(MethodOptOutContract.class, method));
        assertThat(definition.policyFor(method)).isEqualTo(new RpcReferencePolicy(1500, 0, 600));
    }

    @Test
    void validatesEveryApprovalBeforePublishingAnImmutableResolutionSnapshot() throws Exception {
        var properties = approvedProperties();
        var resolver = new RpcReferenceDefinitionResolver(properties);
        resolver.initialize();
        properties.getConsumer().getRetryApprovals().get(RpcReferencePolicy.APPROVED_READ_METHOD)
                .setRetries(0).setAttemptTimeoutMs(1).setTotalTimeoutMs(1);
        properties.getConsumer().getRetryApprovals().clear();
        assertThat(resolver.resolve(Holder.class.getDeclaredField("inherited"), approvedDescriptor())
                .policyFor(EchoContract.class.getMethod("echo", StringValue.class)))
                .isEqualTo(new RpcReferencePolicy(1500, 1, 600));

        var unknown = approvedProperties();
        unknown.getConsumer().getRetryApprovals().put(FULL_METHOD,
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 600, 1500));
        assertInvalid(() -> new RpcReferenceDefinitionResolver(unknown).initialize());
        var unbound = approvedProperties();
        unbound.getConsumer().getBindings().get("identity-directory").setMethods(Set.of(FULL_METHOD));
        assertInvalid(() -> new RpcReferenceDefinitionResolver(unbound).initialize());
        for (var approval : List.of(
                new EgonRpcProperties.RpcRetryApprovalConfig(2, 600, 1500),
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 601, 1500),
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 600, 1501),
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 0, 1500),
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 600, 500))) {
            var invalid = approvedProperties();
            invalid.getConsumer().getRetryApprovals().put(RpcReferencePolicy.APPROVED_READ_METHOD, approval);
            assertInvalid(() -> new RpcReferenceDefinitionResolver(invalid).initialize());
        }
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

    private static EgonRpcProperties approvedProperties() {
        var properties = properties();
        properties.getConsumer().setDefaultTimeoutMs(2000);
        properties.getConsumer().getBindings().get("identity-directory")
                .setMethods(Set.of(RpcReferencePolicy.APPROVED_READ_METHOD));
        properties.getConsumer().getRetryApprovals().put(RpcReferencePolicy.APPROVED_READ_METHOD,
                new EgonRpcProperties.RpcRetryApprovalConfig(1, 600, 1500));
        return properties;
    }

    private RpcContractDescriptor approvedDescriptor() {
        return approvedDescriptor(EchoContract.class, descriptor.methods().getFirst().javaMethod());
    }

    private RpcContractDescriptor approvedDescriptor(Class<?> contractType, java.lang.reflect.Method javaMethod) {
        var original = descriptor.methods().getFirst();
        var method = new RpcMethodDescriptor(javaMethod, "GetTenantMembership",
                RpcReferencePolicy.APPROVED_READ_METHOD, original.idempotent(), original.invocationMode(),
                original.requestType(), original.responseType(), original.grpcMethod(), original.protoMethod());
        return new RpcContractDescriptor(contractType,
                "egon.tianquan.shoubing.v1.IdentityDirectoryService", descriptor.group(), descriptor.version(), List.of(method));
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
        @EgonRpcReference(bindingId = "identity-directory") private EchoContract inherited;
        @EgonRpcReference(bindingId = "identity-directory", retries = 0) private EchoContract optedOut;
        @EgonRpcReference(bindingId = "identity-directory") private MethodOptOutContract methodOptedOut;
    }

    interface MethodOptOutContract {
        @EgonRpcMethod(name = "GetTenantMembership", retries = 0)
        StringValue membership(StringValue request);
    }
}
