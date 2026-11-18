package top.egon.cola.component.rpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EgonRpcPropertiesTest {

    private static final String APPROVED_METHOD =
            "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetTenantMembership";
    private static final String CONSUMER_PREFIX = "egon.cola.component.rpc.consumer.";
    private static final String APPROVAL_PREFIX = CONSUMER_PREFIX + "retry-approvals[" + APPROVED_METHOD + "].";
    private static final String ADMISSION_PREFIX = "egon.cola.component.rpc.provider.admission.";
    private static final String ADMISSION_METHOD_PREFIX = ADMISSION_PREFIX + "methods[" + APPROVED_METHOD + "].";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class))
            .withPropertyValues("egon.cola.component.rpc.enabled=true");

    @Test
    void preservesTransportCapacityAndProcessIdentityDefaults() {
        EgonRpcProperties properties = new EgonRpcProperties();
        assertThat(properties.getProvider().getMaxInboundMessageSize()).isEqualTo(4 * 1024 * 1024);
        assertThat(properties.getProvider().getAdmission().isEnabled()).isFalse();
        assertThat(properties.getProvider().getAdmission().getMaxConcurrent()).isEqualTo(64);
        assertThat(properties.getProvider().getAdmission().getMethods()).isEmpty();
        assertThat(properties.getConsumer().getMaxInboundMessageSize()).isEqualTo(4 * 1024 * 1024);
        assertThat(properties.getConsumer().getGenericCacheMaxEntries()).isEqualTo(256);
        assertThat(properties.getConsumer().getGenericCacheIdleTimeoutMs()).isEqualTo(600_000L);
        assertThat(properties.getConsumer().getBindings()).isEmpty();
        assertThat(properties.getConsumer().getRetryApprovals()).isEmpty();
        assertThat(properties.getIdentity().getEnv()).isEqualTo("default");
        assertThat(properties.getIdentity().getHost()).isNull();
        assertThat(properties.getIdentity().getInstanceId()).isNull();
    }

    @Test
    void validatesConfigurationLimitsInTheBootBinder() {
        contextRunner.withPropertyValues("egon.cola.component.rpc.consumer.generic-cache-max-entries=4097")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("genericCacheMaxEntries");
                });
    }

    @Test
    void rejectsAnIncompleteBindingEvenWithoutAnyReferenceBean() {
        contextRunner.withPropertyValues(
                "egon.cola.component.rpc.consumer.bindings.echo.version=1",
                "egon.cola.component.rpc.consumer.bindings.echo.namespace=permission-prod",
                "egon.cola.component.rpc.consumer.bindings.echo.service=echo-rpc",
                "egon.cola.component.rpc.consumer.bindings.echo.port=18122")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("methods");
                });
    }

    @Test
    void bindsTheExactNativeMethodWithBracketNotationAndAnExplicitBudget() {
        retryContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(EgonRpcProperties.class);
            assertThat(properties.getConsumer().getRetryApprovals()).containsOnlyKeys(APPROVED_METHOD);
            var approval = properties.getConsumer().getRetryApprovals().get(APPROVED_METHOD);
            assertThat(approval.getRetries()).isEqualTo(1);
            assertThat(approval.getAttemptTimeoutMs()).isEqualTo(600);
            assertThat(approval.getTotalTimeoutMs()).isEqualTo(1500);
        });
    }

    @Test
    void rejectsMalformedAndUnboundApprovalKeysEvenWhenTheConsumerIsDisabled() {
        for (String key : new String[]{
                "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetOther",
                "egon.tianquan.shoubing.v1.IdentityDirectoryServiceGetTenantMembership",
                "*"}) {
            retryContextRunner().withPropertyValues(
                    CONSUMER_PREFIX + "retry-approvals[" + key + "].retries=1",
                    CONSUMER_PREFIX + "retry-approvals[" + key + "].attempt-timeout-ms=600",
                    CONSUMER_PREFIX + "retry-approvals[" + key + "].total-timeout-ms=1500")
                    .run(context -> assertThat(context).hasFailed());
        }
        retryContextRunner().withPropertyValues(
                CONSUMER_PREFIX + "bindings.identity-directory.methods[0]=another.v1.Service/Read")
                .run(context -> assertThat(context).hasFailed());
        boundReadContextRunner().withPropertyValues(
                CONSUMER_PREFIX + "retry-approvals." + APPROVED_METHOD + ".retries=1",
                CONSUMER_PREFIX + "retry-approvals." + APPROVED_METHOD + ".attempt-timeout-ms=600",
                CONSUMER_PREFIX + "retry-approvals." + APPROVED_METHOD + ".total-timeout-ms=1500")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsApprovalBudgetsOutsideTheSingleApprovedReadContract() {
        for (String invalid : new String[]{
                "retries=-1", "retries=2", "attempt-timeout-ms=0", "attempt-timeout-ms=601",
                "total-timeout-ms=0", "total-timeout-ms=1501", "total-timeout-ms=500"}) {
            retryContextRunner().withPropertyValues(APPROVAL_PREFIX + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void bindsAdmissionPoliciesByExactNativeMethodAndKeepsAccountingDimensionsExplicit() {
        admissionContextRunner().withPropertyValues(ADMISSION_METHOD_PREFIX + "max-attempts=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var admission = context.getBean(EgonRpcProperties.class).getProvider().getAdmission();
                    assertThat(admission.isEnabled()).isTrue();
                    assertThat(admission.getMethods()).containsOnlyKeys(APPROVED_METHOD);
                    var policy = admission.getMethods().get(APPROVED_METHOD);
                    assertThat(policy.getQuota()).isEqualTo("tenant-membership-read");
                    assertThat(policy.getQuotaCost()).isEqualTo(1);
                    assertThat(policy.getMaxAttempts()).isEqualTo(2);
                    assertThat(policy.getReplayWindow()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(policy.getPhysicalWindow()).isEqualTo(Duration.ofSeconds(1));
                });
        admissionContextRunner().withPropertyValues(ADMISSION_METHOD_PREFIX + "quota-capacity=1",
                        ADMISSION_METHOD_PREFIX + "quota-cost=2")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsAnEnabledAdmissionWithoutAPolicyOrEnabledProvider() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesBindingConfiguration.class)
                .withPropertyValues("egon.cola.component.rpc.provider.enabled=true", ADMISSION_PREFIX + "enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("configurationValid");
                });
        admissionContextRunner().withPropertyValues("egon.cola.component.rpc.provider.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("admissionActivationValid");
                });
    }

    @Test
    void rejectsBroaderMethodGrantsAndUnsupportedAdmissionDimensions() {
        for (String invalid : new String[]{
                "quota=", "quota-capacity=0", "quota-capacity=9007199254740992", "quota-cost=0",
                "quota-cost=9007199254740992", "physical-capacity=0", "physical-capacity=9007199254740992",
                "max-attempts=0", "max-attempts=3", "replay-window=1499ms", "replay-window=300001ms",
                "replay-window=PT1.500000001S", "physical-window=0ms", "physical-window=300001ms",
                "physical-window=PT0.001000001S"}) {
            admissionContextRunner().withPropertyValues(ADMISSION_METHOD_PREFIX + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
        for (String invalid : new String[]{"max-concurrent=0", "max-concurrent=65536", "wait-queue-capacity=1"}) {
            admissionContextRunner().withPropertyValues(ADMISSION_PREFIX + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
        for (String key : new String[]{"*", "/" + APPROVED_METHOD,
                "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetOther"}) {
            String methodPrefix = ADMISSION_PREFIX + "methods[" + key + "].";
            admissionContextRunner().withPropertyValues(methodPrefix + "quota=other",
                            methodPrefix + "quota-capacity=10", methodPrefix + "physical-capacity=10",
                            methodPrefix + "max-attempts=2")
                    .run(context -> assertThat(context).hasFailed());
        }
        new ApplicationContextRunner().withUserConfiguration(PropertiesBindingConfiguration.class)
                .withPropertyValues("egon.cola.component.rpc.provider.enabled=true", ADMISSION_PREFIX + "enabled=true",
                        ADMISSION_PREFIX + "methods." + APPROVED_METHOD + ".quota=tenant-membership-read",
                        ADMISSION_PREFIX + "methods." + APPROVED_METHOD + ".quota-capacity=10",
                        ADMISSION_PREFIX + "methods." + APPROVED_METHOD + ".physical-capacity=10")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner admissionContextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(PropertiesBindingConfiguration.class)
                .withPropertyValues("egon.cola.component.rpc.provider.enabled=true", ADMISSION_PREFIX + "enabled=true",
                        ADMISSION_METHOD_PREFIX + "quota=tenant-membership-read",
                        ADMISSION_METHOD_PREFIX + "quota-capacity=100", ADMISSION_METHOD_PREFIX + "physical-capacity=20");
    }

    private ApplicationContextRunner retryContextRunner() {
        return boundReadContextRunner().withPropertyValues(
                APPROVAL_PREFIX + "retries=1",
                APPROVAL_PREFIX + "attempt-timeout-ms=600",
                APPROVAL_PREFIX + "total-timeout-ms=1500");
    }

    private ApplicationContextRunner boundReadContextRunner() {
        return contextRunner.withPropertyValues(
                CONSUMER_PREFIX + "bindings.identity-directory.version=1",
                CONSUMER_PREFIX + "bindings.identity-directory.namespace=identity-prod",
                CONSUMER_PREFIX + "bindings.identity-directory.service=identity-directory",
                CONSUMER_PREFIX + "bindings.identity-directory.port=9090",
                CONSUMER_PREFIX + "bindings.identity-directory.methods[0]=" + APPROVED_METHOD);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EgonRpcProperties.class)
    static class PropertiesBindingConfiguration {
    }
}
