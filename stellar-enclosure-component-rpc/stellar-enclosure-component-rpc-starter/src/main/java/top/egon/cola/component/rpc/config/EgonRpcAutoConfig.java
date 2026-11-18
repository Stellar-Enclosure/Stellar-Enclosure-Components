package top.egon.cola.component.rpc.config;

import io.grpc.ServerInterceptor;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelFactory;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericTargetCache;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInterceptorFactory;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.lifecycle.RpcConsumerLifecycleCoordinator;
import top.egon.cola.component.rpc.consumer.proxy.EgonRpcReferenceBeanPostProcessor;
import top.egon.cola.component.rpc.consumer.proxy.RpcConsumerProxyFactory;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentityFactory;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentityProvider;
import top.egon.cola.component.rpc.contract.catalog.DefaultRpcContractCatalog;
import top.egon.cola.component.rpc.contract.catalog.RpcContractCatalog;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.provider.binding.RpcProviderBeanScanner;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderAvailabilityRegistry;
import top.egon.cola.component.rpc.provider.lifecycle.RpcProviderLifecycle;
import top.egon.cola.component.rpc.provider.server.RpcProviderExceptionMapper;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerFactory;
import top.egon.cola.component.rpc.provider.server.RpcProviderServerInterceptor;
import top.egon.cola.component.rpc.provider.server.RpcServerServiceDefinitionFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(
        after = ValidationAutoConfiguration.class,
        afterName = "top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration")
@EnableConfigurationProperties(EgonRpcProperties.class)
@ConditionalOnProperty(prefix = "egon.cola.component.rpc", name = "enabled", havingValue = "true")
public class EgonRpcAutoConfig {

    @Bean(name = "egonColaValidationUtils")
    @ConditionalOnMissingBean(name = "egonColaValidationUtils")
    public ValidationUtils egonColaValidationUtils(ObjectProvider<Validator> validators) {
        return new ValidationUtils(validators.getIfAvailable(
                () -> Validation.buildDefaultValidatorFactory().getValidator()));
    }

    @Bean(name = "rpcContractValidator")
    @ConditionalOnMissingBean
    public RpcContractValidator rpcContractValidator(
            @Qualifier("egonColaValidationUtils") ValidationUtils validationUtils) {
        return new RpcContractValidator(validationUtils);
    }

    @Bean(name = "rpcProcessIdentityProvider")
    @ConditionalOnMissingBean
    public RpcProcessIdentityProvider rpcProcessIdentityProvider(
            Environment environment, EgonRpcProperties properties) {
        RpcProcessIdentityFactory factory = new RpcProcessIdentityFactory(environment, properties);
        return factory::create;
    }

    @Bean(name = "rpcProcessIdentity")
    @ConditionalOnMissingBean
    public RpcProcessIdentity rpcProcessIdentity(RpcProcessIdentityProvider identityProvider) {
        return identityProvider.identity();
    }

    @Bean(name = "rpcProviderMethodRegistry")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.provider", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcProviderMethodRegistry rpcProviderMethodRegistry(
            ApplicationContext applicationContext, RpcContractValidator contractValidator) {
        return new RpcProviderBeanScanner(applicationContext, contractValidator).scan();
    }

    @Bean(name = "rpcContractCatalog")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.provider", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcContractCatalog rpcContractCatalog(RpcProviderMethodRegistry methodRegistry) {
        return new DefaultRpcContractCatalog(methodRegistry);
    }

    @Bean(name = "rpcProviderLifecycle")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.provider", name = "enabled", havingValue = "true")
    public RpcProviderLifecycle rpcProviderLifecycle(
            RpcProviderMethodRegistry methodRegistry,
            EgonRpcProperties properties,
            ObjectProvider<ServerInterceptor> serverInterceptors,
            ObjectProvider<RpcProviderExceptionMapper> exceptionMappers) {
        RpcProviderAvailabilityRegistry availability = new RpcProviderAvailabilityRegistry();
        List<ServerInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new RpcProviderServerInterceptor());
        serverInterceptors.orderedStream().forEach(interceptors::add);
        return new RpcProviderLifecycle(
                methodRegistry,
                new RpcServerServiceDefinitionFactory(availability, exceptionMappers.orderedStream().toList()),
                new RpcProviderServerFactory(properties.getProvider().getMaxInboundMessageSize()),
                availability,
                interceptors,
                properties);
    }

    @Bean(name = "rpcConsumerChannelFactory")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcConsumerChannelFactory rpcConsumerChannelFactory(EgonRpcProperties properties) {
        EgonRpcProperties.Consumer consumer = properties.getConsumer();
        return new RpcConsumerChannelFactory(consumer.getMaxInboundMessageSize(), consumer.getConnectTimeoutMs());
    }

    @Bean(name = "rpcConsumerChannelPool")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcConsumerChannelPool rpcConsumerChannelPool(
            RpcConsumerChannelFactory channelFactory, EgonRpcProperties properties) {
        return new RpcConsumerChannelPool(
                channelFactory, Duration.ofMillis(properties.getConsumer().getChannelDrainTimeoutMs()));
    }

    @Bean(name = "rpcReferenceStrategyFactory")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcReferenceStrategyFactory rpcReferenceStrategyFactory(RpcConsumerChannelPool channelPool) {
        return new RpcReferenceStrategyFactory(channelPool);
    }

    @Bean(name = "rpcReferenceDefinitionResolver")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcReferenceDefinitionResolver rpcReferenceDefinitionResolver(
            EgonRpcProperties properties, ApplicationContext applicationContext) {
        return new RpcReferenceDefinitionResolver(properties, applicationContext);
    }

    @Bean(name = "rpcGenericTargetCache")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcGenericTargetCache rpcGenericTargetCache(
            RpcReferenceDefinitionResolver definitionResolver,
            RpcReferenceStrategyFactory strategyFactory,
            EgonRpcProperties properties) {
        EgonRpcProperties.Consumer consumer = properties.getConsumer();
        return new RpcGenericTargetCache(definitionResolver, strategyFactory,
                consumer.getGenericCacheMaxEntries(), Duration.ofMillis(consumer.getGenericCacheIdleTimeoutMs()));
    }

    @Bean(name = "rpcConsumerLifecycleCoordinator")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcConsumerLifecycleCoordinator rpcConsumerLifecycleCoordinator(
            RpcConsumerChannelPool channelPool,
            RpcGenericTargetCache genericTargetCache,
            RpcReferenceStrategyFactory strategyFactory) {
        return new RpcConsumerLifecycleCoordinator(channelPool, List.of(strategyFactory, genericTargetCache));
    }

    @Bean(name = "rpcInvocationExecutor")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcInvocationExecutor rpcInvocationExecutor(RpcConsumerLifecycleCoordinator lifecycleCoordinator) {
        return new RpcInvocationExecutor(lifecycleCoordinator, new RpcStatusExceptionMapper());
    }

    @Bean(name = "rpcGenericInvoker")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcGenericInvoker rpcGenericInvoker(
            RpcGenericTargetCache targetCache,
            RpcInvocationExecutor executor,
            RpcProcessIdentity processIdentity,
            ObjectProvider<RpcClientInterceptorFactory> interceptorFactories) {
        return new RpcGenericInvoker(targetCache, executor, processIdentity, interceptorFactories.orderedStream().toList());
    }

    @Bean(name = "rpcConsumerProxyFactory")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RpcConsumerProxyFactory rpcConsumerProxyFactory(
            RpcInvocationExecutor executor,
            RpcProcessIdentity processIdentity,
            ObjectProvider<RpcClientInterceptorFactory> interceptorFactories) {
        return new RpcConsumerProxyFactory(executor, processIdentity, interceptorFactories.orderedStream().toList());
    }

    @Bean(name = "egonRpcReferenceBeanPostProcessor")
    @ConditionalOnProperty(prefix = "egon.cola.component.rpc.consumer", name = "enabled", havingValue = "true")
    public EgonRpcReferenceBeanPostProcessor egonRpcReferenceBeanPostProcessor(
            RpcContractValidator contractValidator,
            RpcReferenceDefinitionResolver definitionResolver,
            RpcReferenceStrategyFactory strategyFactory,
            RpcConsumerProxyFactory proxyFactory) {
        return new EgonRpcReferenceBeanPostProcessor(contractValidator, definitionResolver, strategyFactory, proxyFactory);
    }
}
