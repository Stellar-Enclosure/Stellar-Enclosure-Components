package top.egon.cola.component.rpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.accessguard.store.LogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.RateLimitBackend;
import top.egon.cola.component.rpc.provider.server.RpcAccessGuardExceptionMapper;
import top.egon.cola.component.rpc.provider.server.RpcAdmissionIdentityResolver;
import top.egon.cola.component.rpc.provider.server.RpcProviderAdmissionComponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RpcAccessGuardAutoConfigurationTest {

    private static final String METHOD = "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetTenantMembership";
    private static final String METHOD_PREFIX = "egon.cola.component.rpc.provider.admission.methods[" + METHOD + "].";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            RpcAccessGuardAutoConfiguration.class));

    @Test
    void providerEnabledInstallsOptionalGuardMapper() {
        contextRunner
                .withPropertyValues(
                        "egon.cola.component.rpc.provider.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            RpcAccessGuardExceptionMapper.class);
                    assertThat(context).doesNotHaveBean("rpcProviderAdmissionComponent");
                });
    }

    @Test
    void disabledOrConsumerOnlyDoesNotInstallProviderMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(
                    RpcAccessGuardExceptionMapper.class);
        });
        contextRunner
                .withPropertyValues(
                        "egon.cola.component.rpc.provider.enabled=false",
                        "egon.cola.component.rpc.consumer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            RpcAccessGuardExceptionMapper.class);
                });
    }

    @Test
    void createsTheNamedAdmissionOnlyWithAllTrustedDependencies() {
        admissionContextRunner()
                .withBean(RpcAdmissionIdentityResolver.class, () -> mock(RpcAdmissionIdentityResolver.class))
                .withBean(RateLimitBackend.class, () -> mock(RateLimitBackend.class))
                .withBean(LogicalQuotaBackend.class, () -> mock(LogicalQuotaBackend.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RpcProviderAdmissionComponent.class);
                    assertThat(context.getBean("rpcProviderAdmissionComponent")).isInstanceOf(RpcProviderAdmissionComponent.class);
                });
    }

    @Test
    void refusesAnEnabledAdmissionWhenAnyTrustedDependencyIsAbsent() {
        admissionContextRunner()
                .withBean(RateLimitBackend.class, () -> mock(RateLimitBackend.class))
                .withBean(LogicalQuotaBackend.class, () -> mock(LogicalQuotaBackend.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("RpcAdmissionIdentityResolver");
                });
        admissionContextRunner()
                .withBean(RpcAdmissionIdentityResolver.class, () -> mock(RpcAdmissionIdentityResolver.class))
                .withBean(LogicalQuotaBackend.class, () -> mock(LogicalQuotaBackend.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("RateLimitBackend");
                });
        admissionContextRunner()
                .withBean(RpcAdmissionIdentityResolver.class, () -> mock(RpcAdmissionIdentityResolver.class))
                .withBean(RateLimitBackend.class, () -> mock(RateLimitBackend.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("LogicalQuotaBackend");
                });
    }

    @Test
    void doesNotLetAPreinstalledManagedComponentSkipTrustedDependencyValidation() {
        admissionContextRunner()
                .withBean("rpcProviderAdmissionComponent", RpcProviderAdmissionComponent.class,
                        () -> mock(RpcProviderAdmissionComponent.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void remainsClassSafeWithoutAccessGuardAndFailsClosedWhenAdmissionIsRequested() {
        var withoutGuard = new FilteredClassLoader("top.egon.cola.component.accessguard");
        new ApplicationContextRunner().withClassLoader(withoutGuard)
                .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class, RpcAccessGuardAutoConfiguration.class))
                .withPropertyValues("egon.cola.component.rpc.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("rpcProviderAdmissionComponent");
                    assertThat(context).doesNotHaveBean("rpcAccessGuardExceptionMapper");
                });
        admissionContextRunner().withClassLoader(withoutGuard)
                .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class))
                .withPropertyValues("egon.cola.component.rpc.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("requires the Access Guard dependency");
                });
    }

    private ApplicationContextRunner admissionContextRunner() {
        return contextRunner.withPropertyValues("egon.cola.component.rpc.provider.enabled=true",
                "egon.cola.component.rpc.provider.admission.enabled=true", METHOD_PREFIX + "quota=membership-read",
                METHOD_PREFIX + "quota-capacity=100", METHOD_PREFIX + "physical-capacity=20");
    }
}
