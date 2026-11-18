package top.egon.cola.component.rpc.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import top.egon.cola.component.accessguard.store.LogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.RateLimitBackend;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.provider.server.RpcAdmissionIdentityResolver;
import top.egon.cola.component.rpc.provider.server.RpcAccessGuardExceptionMapper;
import top.egon.cola.component.rpc.provider.server.RpcProviderAdmissionComponent;

/** Optional, class-safe Access Guard to RPC Provider adapter. */
@AutoConfiguration(
        after = ValidationAutoConfiguration.class,
        afterName = {
                "top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration",
                "top.egon.cola.component.accessguard.autoconfigure.AccessGuardAopAutoConfiguration"
        })
@ConditionalOnClass(name = {
        "top.egon.cola.component.accessguard.common.exception.AccessGuardRejectedException",
        "top.egon.cola.component.accessguard.store.RateLimitBackend",
        "top.egon.cola.component.accessguard.store.LogicalQuotaBackend"
})
@EnableConfigurationProperties(EgonRpcProperties.class)
@ConditionalOnProperty(
        prefix = "egon.cola.component.rpc.provider",
        name = "enabled",
        havingValue = "true"
)
public class RpcAccessGuardAutoConfiguration {

    @Bean(name = "egonColaValidationUtils")
    @ConditionalOnMissingBean(name = "egonColaValidationUtils")
    public ValidationUtils egonColaValidationUtils(ObjectProvider<Validator> validators) {
        return new ValidationUtils(validators.getIfAvailable(
                () -> Validation.buildDefaultValidatorFactory().getValidator()));
    }

    @Bean
    @ConditionalOnMissingBean(RpcAccessGuardExceptionMapper.class)
    public RpcAccessGuardExceptionMapper rpcAccessGuardExceptionMapper() {
        return new RpcAccessGuardExceptionMapper();
    }

    @Bean(name = "rpcProviderAdmissionComponent")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.provider.admission", name = "enabled", havingValue = "true")
    public RpcProviderAdmissionComponent rpcProviderAdmissionComponent(
            RpcAdmissionIdentityResolver identityResolver,
            RateLimitBackend physicalRateBackend,
            LogicalQuotaBackend logicalQuotaBackend,
            EgonRpcProperties properties) {
        return new RpcProviderAdmissionComponent(identityResolver, physicalRateBackend, logicalQuotaBackend,
                properties.getProvider().getAdmission());
    }
}
