package top.egon.cola.component.rpc.tianshu.autoconfigure;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.rpc.tianshu.si.DdcSiCatalogRpcClientTest;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DdcRpcAutoConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DdcRpcAutoConfiguration.class));
    private static final String PREFIX=DdcRpcProperties.PREFIX+".";
    private static final String[] ENABLED={PREFIX+"enabled=true",PREFIX+"binding-id=si",PREFIX+"binding-version=1",PREFIX+"default-timeout=2s"};

    @Test void defaultContextHasCanonicalMapperButNoChannelsStubsClientsOrLegacyBeans(){
        runner.run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(DdcSiProtoMapper.class).hasBean("ddcSiProtoMapperImpl")
                    .doesNotHaveBean(DdcSiCatalogRpcClient.class).doesNotHaveBean(ManagedChannel.class)
                    .doesNotHaveBean(DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceBlockingStub.class)
                    .doesNotHaveBean("ddcRpcClientFactory").doesNotHaveBean("rpcProviderRegistry").doesNotHaveBean("ddcConfigClient");
        });
    }
    @Test void optInUsesOnlyAnExistingNativeInvokerAndApprovedServiceCredentials(){
        runner.withUserConfiguration(Fixture.class).withPropertyValues(ENABLED).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(DdcSiCatalogRpcClient.class).doesNotHaveBean(ManagedChannel.class);
            verify(context.getBean(IdpRpcClientCredentialInterceptorFactory.class),atLeastOnce()).validateConfiguration();
        });
    }
    @Test void optInWithoutNativeDependenciesOrWithWrongVersionFailsClosed(){
        runner.withPropertyValues(ENABLED).run(context->assertThat(context).hasFailed());
        runner.withUserConfiguration(Fixture.class).withPropertyValues(ENABLED).withPropertyValues(PREFIX+"binding-version=2")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void everyNativeApprovedMethodRequiresItsExactServiceBinding(){
        runner.withUserConfiguration(Fixture.class).withPropertyValues(ENABLED).withBean("idpStarterProperties",IdpStarterProperties.class,
                ()->DdcSiCatalogRpcClientTest.identityProperties(Set.of(DdcSiCatalogRpcClient.RESOLVE)))
                .run(context->assertThat(context).hasFailed());
    }
    @Test void userRelayOrWrongTransportReferenceCannotStandInForServiceCredentials(){
        for(boolean relay:new boolean[]{false,true}){
            var idp=DdcSiCatalogRpcClientTest.identityProperties(DdcSiCatalogRpcClient.METHODS);
            idp.getRpc().getConsumer().getBindings().values().forEach(binding->{
                if(relay)binding.setMode(IdpStarterProperties.RpcConsumerModeEnum.USER_RELAY);
                else binding.setTransportBindingVersion(2L);
            });
            runner.withUserConfiguration(Fixture.class).withPropertyValues(ENABLED)
                    .withBean("idpStarterProperties",IdpStarterProperties.class,()->idp).run(context->assertThat(context).hasFailed());
        }
    }
    @Test void oldTargetTlsHmacAndNewUnknownFieldsAreRejectedEvenWhenDisabled(){
        for(String property:new String[]{"egon.cola.component.tianshu.rpc.target=dns:///retired", "egon.cola.component.tianshu.rpc.tls.enabled=false",
                "egon.cola.component.tianshu.rpc.auth.runtime.secret=not-a-credential",PREFIX+"target=dns:///retired"}){
            runner.withPropertyValues(property).run(context->assertThat(context).hasFailed());
        }
    }
    @Test void zeroAndUnboundedDeadlinesAreRejected(){
        for(String timeout:new String[]{"0ms","6s","-1s"})runner.withUserConfiguration(Fixture.class).withPropertyValues(ENABLED)
                .withPropertyValues(PREFIX+"default-timeout="+timeout).run(context->assertThat(context).hasFailed());
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fixture {
        @Bean("rpcGenericInvoker") RpcGenericInvoker invoker(){return mock(RpcGenericInvoker.class);}
        @Bean("egon.cola.component.rpc-top.egon.cola.component.rpc.config.EgonRpcProperties") EgonRpcProperties properties(){return DdcSiCatalogRpcClientTest.rpcProperties(DdcSiCatalogRpcClient.METHODS);}
        @Bean("rpcReferenceDefinitionResolver") RpcReferenceDefinitionResolver definitions(
                @Qualifier("egon.cola.component.rpc-top.egon.cola.component.rpc.config.EgonRpcProperties") EgonRpcProperties properties){return new RpcReferenceDefinitionResolver(properties);}
        @Bean("rpcProcessIdentity") RpcProcessIdentity process(){return DdcSiCatalogRpcClientTest.NativeRuntime.PROCESS;}
        @Bean("idpStarterProperties") @ConditionalOnMissingBean(name="idpStarterProperties") IdpStarterProperties idp(){return DdcSiCatalogRpcClientTest.identityProperties(DdcSiCatalogRpcClient.METHODS);}
        @Bean("idpRpcClientCredentialInterceptorFactory") IdpRpcClientCredentialInterceptorFactory credentials(){return mock(IdpRpcClientCredentialInterceptorFactory.class);}
    }
}
