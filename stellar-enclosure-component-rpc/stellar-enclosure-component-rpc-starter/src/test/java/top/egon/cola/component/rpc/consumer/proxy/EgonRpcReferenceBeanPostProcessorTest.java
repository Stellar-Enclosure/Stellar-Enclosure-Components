package top.egon.cola.component.rpc.consumer.proxy;

import com.google.protobuf.StringValue;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.annotation.EgonRpcService;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinition;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;
import top.egon.cola.component.rpc.support.TestGrpcDescriptorFixtures.UnaryFixtureGrpc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EgonRpcReferenceBeanPostProcessorTest {
    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void injectsOneTrustedBindingAndRetainsNativeIdentity() {
        RpcReferenceStrategyFactory strategies = mock(RpcReferenceStrategyFactory.class);
        RpcReferenceStrategy strategy = mock(RpcReferenceStrategy.class);
        RpcConsumerProxyFactory proxies = mock(RpcConsumerProxyFactory.class);
        SampleContract proxy = mock(SampleContract.class);
        when(strategies.create(any(RpcReferenceDefinition.class))).thenReturn(strategy);
        doReturn(proxy).when(proxies).create(any(RpcContractDescriptor.class), any(RpcReferenceDefinition.class), any(RpcReferenceStrategy.class));
        References bean = new References();

        processor(strategies, proxies).postProcessBeforeInitialization(bean, "references");

        assertThat(bean.reference).isSameAs(proxy);
        verify(strategies).create(argThat(definition -> definition.target().bindingId().equals("sample-binding")
                && definition.target().dnsTarget().equals("dns:///sample-rpc.apps-prod.svc.cluster.local:18122")
                && definition.serviceIdentity().serviceName().equals("egon.rpc.fixture.v1.UnaryFixtureService")
                && definition.serviceIdentity().group().equals("default")));
    }

    @Test
    void rejectsUnknownBindingBeforeCreatingAnyStrategy() {
        RpcReferenceStrategyFactory strategies = mock(RpcReferenceStrategyFactory.class);
        assertThatThrownBy(() -> processor(strategies, mock(RpcConsumerProxyFactory.class))
                .postProcessBeforeInitialization(new UnknownReference(), "unknownClient"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknownClient")
                .hasMessageContaining("reference").hasMessageContaining("binding is not configured");
        verifyNoInteractions(strategies);
    }

    @Test
    void closesStrategyIfProxyCompilationFails() {
        RpcReferenceStrategyFactory strategies = mock(RpcReferenceStrategyFactory.class);
        RpcReferenceStrategy strategy = mock(RpcReferenceStrategy.class);
        when(strategies.create(any(RpcReferenceDefinition.class))).thenReturn(strategy);
        RpcConsumerProxyFactory proxies = mock(RpcConsumerProxyFactory.class);
        when(proxies.create(any(), any(), any())).thenThrow(new IllegalArgumentException("invalid proxy"));

        assertThatThrownBy(() -> processor(strategies, proxies)
                .postProcessBeforeInitialization(new References(), "brokenClient"))
                .hasMessageContaining("brokenClient").hasMessageContaining("invalid proxy");
        verify(strategy).close();
    }

    @Test
    void reportsStableTargetCreationFailureWithBeanAndField() {
        RpcReferenceStrategyFactory strategies = mock(RpcReferenceStrategyFactory.class);
        when(strategies.create(any(RpcReferenceDefinition.class))).thenThrow(new IllegalStateException("stable target unavailable"));
        assertThatThrownBy(() -> processor(strategies, mock(RpcConsumerProxyFactory.class))
                .postProcessBeforeInitialization(new References(), "unavailableClient"))
                .hasMessageContaining("unavailableClient").hasMessageContaining("reference").hasMessageContaining("stable target unavailable");
    }

    @Test
    void keepsInvalidContractFailures() {
        assertThatThrownBy(() -> processor(mock(RpcReferenceStrategyFactory.class), mock(RpcConsumerProxyFactory.class))
                .postProcessBeforeInitialization(new MissingServiceReference(), "missingServiceClient"))
                .isInstanceOfSatisfying(EgonRpcException.class,
                        exception -> assertThat(exception.getRpcErrorCode()).isEqualTo(EgonRpcErrorCode.RPC_INVALID_CONTRACT));
        assertThatThrownBy(() -> processor(mock(RpcReferenceStrategyFactory.class), mock(RpcConsumerProxyFactory.class))
                .postProcessBeforeInitialization(new NonInterfaceReference(), "invalidFieldClient"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalidFieldClient").hasMessageContaining("interface");
    }

    private EgonRpcReferenceBeanPostProcessor processor(RpcReferenceStrategyFactory strategies, RpcConsumerProxyFactory proxies) {
        EgonRpcProperties properties = new EgonRpcProperties();
        properties.getConsumer().getBindings().put("sample-binding", new EgonRpcProperties.RpcServiceBindingConfig()
                .setVersion(1).setNamespace("apps-prod").setService("sample-rpc").setPort(18122)
                .setMethods(Set.of("egon.rpc.fixture.v1.UnaryFixtureService/Echo")));
        return new EgonRpcReferenceBeanPostProcessor(new RpcContractValidator(VALIDATION_UTILS),
                new RpcReferenceDefinitionResolver(properties), strategies, proxies);
    }

    private static final class References {
        @EgonRpcReference(bindingId = "sample-binding", timeoutMs = 1200)
        private SampleContract reference;
    }
    private static final class UnknownReference {
        @EgonRpcReference(bindingId = "missing-binding")
        private SampleContract reference;
    }
    private static final class MissingServiceReference {
        @EgonRpcReference(bindingId = "sample-binding")
        private MissingServiceContract reference;
    }
    private static final class NonInterfaceReference {
        @EgonRpcReference(bindingId = "sample-binding")
        private String reference;
    }
    @EgonRpcService(grpcClass = UnaryFixtureGrpc.class, group = "default", version = "1.0.0")
    private interface SampleContract {
        @EgonRpcMethod(name = "Echo")
        StringValue echo(StringValue request);
    }
    private interface MissingServiceContract {
        @EgonRpcMethod(name = "Echo")
        StringValue echo(StringValue request);
    }
}
