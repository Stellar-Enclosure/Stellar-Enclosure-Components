package top.egon.cola.component.rpc.tianshu.autoconfigure;

import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import top.egon.cola.component.rpc.config.EgonRpcAutoConfig;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;

import java.util.Locale;
import java.util.Set;

/** SI-only assembly; the host's native runtime owns channels, credentials, attempts and shutdown. */
@AutoConfiguration(after = EgonRpcAutoConfig.class,
        afterName = "top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterAutoConfiguration")
@EnableConfigurationProperties(DdcRpcProperties.class)
public class DdcRpcAutoConfiguration {
    private static final String LEGACY_PREFIX = "egon.cola.component.tianshu.rpc";

    @Bean("ddcSiNativeBootstrapGuard")
    public InitializingBean ddcSiNativeBootstrapGuard(Environment environment) {
        return () -> {
            if (environment instanceof ConfigurableEnvironment configurable) {
                for (var source : configurable.getPropertySources()) {
                    if (source instanceof EnumerablePropertySource<?> enumerable) {
                        for (String name : enumerable.getPropertyNames()) {
                            String normalized = name.toLowerCase(Locale.ROOT).replace('_', '.');
                            if (normalized.equals(LEGACY_PREFIX) || normalized.startsWith(LEGACY_PREFIX + ".")) {
                                throw retired();
                            }
                        }
                    }
                }
            }
            for (String key : Set.of("target", "connect-timeout", "default-timeout", "load-balancing-policy",
                    "max-inbound-message-size", "shutdown-timeout", "tls.enabled", "auth.runtime.credential-id")) {
                if (environment.containsProperty(LEGACY_PREFIX + "." + key)) { throw retired(); }
            }
        };
    }

    @Bean("ddcSiProtoMapperImpl")
    @ConditionalOnMissingBean(name = "ddcSiProtoMapperImpl")
    public DdcSiProtoMapper ddcSiProtoMapper() { return Mappers.getMapper(DdcSiProtoMapper.class); }

    @Bean("ddcSiCatalogRpcClient")
    @DependsOn("ddcSiNativeBootstrapGuard")
    @ConditionalOnProperty(prefix = DdcRpcProperties.PREFIX, name = "enabled", havingValue = "true")
    public DdcSiCatalogRpcClient ddcSiCatalogRpcClient(
            @Qualifier("rpcGenericInvoker") RpcGenericInvoker invoker,
            @Qualifier("ddcSiProtoMapperImpl") DdcSiProtoMapper mapper,
            @Qualifier(DdcRpcProperties.BEAN_NAME) DdcRpcProperties properties,
            @Qualifier("rpcReferenceDefinitionResolver") RpcReferenceDefinitionResolver definitions,
            @Qualifier("egon.cola.component.rpc-top.egon.cola.component.rpc.config.EgonRpcProperties") EgonRpcProperties nativeProperties,
            @Qualifier("rpcProcessIdentity") RpcProcessIdentity process,
            @Qualifier("idpStarterProperties") IdpStarterProperties identity,
            @Qualifier("idpRpcClientCredentialInterceptorFactory") IdpRpcClientCredentialInterceptorFactory credentials) {
        credentials.validateConfiguration();
        var configured = nativeProperties.getConsumer().getBindings().get(properties.getBindingId());
        if (configured == null || configured.getMethods() == null || configured.getMethods().isEmpty()) { throw invalid(); }
        Set<String> approvedMethods = Set.copyOf(configured.getMethods());
        var target = definitions.resolve(properties.getBindingId(), new RpcServiceIdentity(DdcSiCatalogRpcClient.SERVICE,
                DdcSiCatalogRpcClient.GROUP, DdcSiCatalogRpcClient.VERSION), approvedMethods).target();
        if (!target.methods().equals(approvedMethods) || target.version() != properties.getBindingVersion()
                || !DdcSiCatalogRpcClient.METHODS.containsAll(target.methods())
                || !identity.getRpc().isEnabled() || !identity.getRpc().getConsumer().isEnabled()) { throw invalid(); }
        for (String method : target.methods()) {
            var matches = identity.getRpc().getConsumer().getBindings().values().stream().filter(binding ->
                    DdcSiCatalogRpcClient.SERVICE.equals(binding.getServiceName()) && DdcSiCatalogRpcClient.GROUP.equals(binding.getGroup())
                            && DdcSiCatalogRpcClient.VERSION.equals(binding.getVersion()) && method.equals(binding.getFullMethodName())
                            && binding.getProcessIdentity() != null
                            && process.applicationName().equals(binding.getProcessIdentity().get("applicationName"))
                            && process.env().equals(binding.getProcessIdentity().get("env"))).toList();
            if (matches.size() != 1) { throw invalid(); }
            var binding = matches.getFirst();
            if (binding.getMode() != IdpStarterProperties.RpcConsumerModeEnum.SERVICE
                    || binding.getScopeContext() != ServiceTokenContext.TENANT
                    || !target.bindingId().equals(binding.getTransportBindingId())
                    || !Long.valueOf(target.version()).equals(binding.getTransportBindingVersion())
                    || binding.getResourceUri() == null || binding.getScopes() == null || binding.getScopes().isEmpty()
                    || binding.getTenantRegistrationIds() == null || binding.getTenantRegistrationIds().isEmpty()) { throw invalid(); }
        }
        return new DdcSiCatalogRpcClient(invoker, mapper, target, properties.getDefaultTimeout());
    }

    private static IllegalStateException retired() { return new IllegalStateException("DDC_LEGACY_RPC_CONFIGURATION_RETIRED"); }
    private static IllegalStateException invalid() { return new IllegalStateException("SI_NATIVE_SERVICE_CREDENTIAL_BINDING_REQUIRED"); }
}
