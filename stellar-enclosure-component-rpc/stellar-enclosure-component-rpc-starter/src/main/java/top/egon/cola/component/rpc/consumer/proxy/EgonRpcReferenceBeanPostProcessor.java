package top.egon.cola.component.rpc.consumer.proxy;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinition;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.validation.RpcContractValidator;

/** Resolves each annotated field once and installs one stable-binding CGLIB proxy. */
public class EgonRpcReferenceBeanPostProcessor implements BeanPostProcessor {

    private final RpcContractValidator contractValidator;
    private final RpcReferenceDefinitionResolver definitionResolver;
    private final RpcReferenceStrategyFactory strategyFactory;
    private final RpcConsumerProxyFactory proxyFactory;

    public EgonRpcReferenceBeanPostProcessor(
            RpcContractValidator contractValidator,
            RpcReferenceDefinitionResolver definitionResolver,
            RpcReferenceStrategyFactory strategyFactory,
            RpcConsumerProxyFactory proxyFactory) {
        this.contractValidator = java.util.Objects.requireNonNull(
                contractValidator,
                "contractValidator"
        );
        this.definitionResolver = java.util.Objects.requireNonNull(
                definitionResolver,
                "definitionResolver"
        );
        this.strategyFactory = java.util.Objects.requireNonNull(
                strategyFactory,
                "strategyFactory"
        );
        this.proxyFactory = java.util.Objects.requireNonNull(
                proxyFactory,
                "proxyFactory"
        );
    }

    @Override
    public Object postProcessBeforeInitialization(
            Object bean,
            String beanName) throws BeansException {
        ReflectionUtils.doWithFields(
                bean.getClass(),
                field -> inject(bean, beanName, field),
                field -> field.isAnnotationPresent(EgonRpcReference.class)
        );
        return bean;
    }

    private void inject(
            Object bean,
            String beanName,
            java.lang.reflect.Field field) {
        if (!field.getType().isInterface()) {
            throw injectionFailure(
                    beanName,
                    field.getName(),
                    annotationName(),
                    "field must be an interface"
            );
        }
        ReflectionUtils.makeAccessible(field);
        RpcContractDescriptor descriptor = contractValidator.validate(
                field.getType()
        );
        RpcReferenceDefinition definition;
        try {
            definition = definitionResolver.resolve(field, descriptor);
        } catch (RuntimeException exception) {
            throw injectionFailure(
                    beanName,
                    field.getName(),
                    annotationName(),
                    exception.getMessage(),
                    exception
            );
        }
        RpcReferenceStrategy strategy;
        try {
            strategy = strategyFactory.create(definition);
        } catch (RuntimeException exception) {
            throw injectionFailure(
                    beanName,
                    field.getName(),
                    annotationName(),
                    exception.getMessage(),
                    exception
            );
        }
        try {
            Object proxy = proxyFactory.create(
                    descriptor,
                    definition,
                    strategy
            );
            ReflectionUtils.setField(field, bean, proxy);
        } catch (RuntimeException exception) {
            strategy.close();
            throw injectionFailure(
                    beanName,
                    field.getName(),
                    annotationName(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private String annotationName() {
        return "@EgonRpcReference";
    }

    private IllegalStateException injectionFailure(
            String beanName,
            String fieldName,
            String annotation,
            String message) {
        return injectionFailure(
                beanName,
                fieldName,
                annotation,
                message,
                null
        );
    }

    private IllegalStateException injectionFailure(
            String beanName,
            String fieldName,
            String annotation,
            String message,
            Throwable cause) {
        String detail = message == null || message.isBlank()
                ? "reference resolution failed" : message;
        return new IllegalStateException(
                "RPC reference injection failed for bean '"
                        + beanName + "', field '" + fieldName + "': "
                        + annotation + " " + detail,
                cause
        );
    }
}
