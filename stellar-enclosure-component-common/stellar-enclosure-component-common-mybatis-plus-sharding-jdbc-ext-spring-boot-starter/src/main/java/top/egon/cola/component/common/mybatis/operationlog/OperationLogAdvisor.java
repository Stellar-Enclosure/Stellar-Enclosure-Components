package top.egon.cola.component.common.mybatis.operationlog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class OperationLogAdvisor implements PointcutAdvisor, Ordered {

    @Qualifier("operationLogRecorder")
    private final OperationLogRecorder recorder;
    @Qualifier("beanFactory")
    private final BeanFactory beans;

    private final Pointcut pointcut = new StaticMethodMatcherPointcut() {
        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return metadata(method, targetClass) != null;
        }
    };
    private final MethodInterceptor advice = this::capture;

    @Override
    public Advice getAdvice() {
        return advice;
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public boolean isPerInstance() {
        return true;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private Object capture(MethodInvocation invocation) throws Throwable {
        Class<?> type = AopUtils.getTargetClass(Objects.requireNonNull(invocation.getThis()));
        Method method = actualMethod(invocation.getMethod(), type);
        OperationLog operation = Objects.requireNonNull(metadata(invocation.getMethod(), type));
        var scope = recorder.sourceScope();
        if (scope.advising) {
            return invocation.proceed();
        }
        var snapshot = recorder.snapshot();
        long start = System.nanoTime();
        scope.advising = true;
        try {
            Object result = invocation.proceed();
            long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            try {
                beans.getBean(operation.projector(), OperationLogProjection.class)
                        .project(invocation.getArguments(), result)
                        .ifPresent(fact -> recorder.record(scope, fact.toBuilder()
                                .actionCode(operation.actionCode()).resourceType(operation.resourceType()).build(),
                                snapshot, OperationLogRecorder.signature(method), durationMs));
            } catch (RuntimeException failure) {
                recorder.rollbackSource();
                throw failure;
            }
            return result;
        } finally {
            scope.advising = false;
        }
    }

    void verify(Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);
        MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<OperationLog>)
                method -> metadata(method, type)).forEach((method, operation) -> {
                    if (operation.actionCode().isBlank() || operation.resourceType().isBlank()
                            || !beans.isTypeMatch(operation.projector(), OperationLogProjection.class)) {
                        throw new IllegalStateException("OPERATION_LOG_PROJECTION_INVALID: " + method.getName());
                    }
                    if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())
                            || !(bean instanceof Advised advised) || AopUtils.isCglibProxy(bean)
                                    && Modifier.isFinal(method.getModifiers())) {
                        throw new IllegalStateException("OPERATION_LOG_METHOD_NOT_PROXYABLE: " + method.getName());
                    }
                    if (AopUtils.isJdkDynamicProxy(bean) && !exposedOnProxyInterface(bean, type, method)) {
                        throw new IllegalStateException("OPERATION_LOG_METHOD_NOT_PROXYABLE: " + method.getName());
                    }
                    verifyTransactionAdvice(advised.getAdvisors(), method, type);
                });
    }

    private static boolean exposedOnProxyInterface(Object bean, Class<?> type, Method method) {
        Method actual = actualMethod(method, type);
        return Arrays.stream(ClassUtils.getAllInterfacesForClass(type))
                .filter(candidate -> candidate.isInstance(bean))
                .flatMap(candidate -> Arrays.stream(candidate.getMethods()))
                .anyMatch(candidate -> actual.equals(actualMethod(candidate, type)));
    }

    private void verifyTransactionAdvice(Advisor[] advisors, Method method, Class<?> type) {
        boolean transactionWrapsAudit = false;
        for (Advisor candidate : advisors) {
            if (candidate == this && transactionWrapsAudit) {
                return;
            }
            if (candidate.getAdvice() instanceof TransactionInterceptor interceptor
                    && interceptor.getTransactionAttributeSource() != null
                    && interceptor.getTransactionAttributeSource().hasTransactionAttribute(method, type)) {
                transactionWrapsAudit = true;
            }
        }
        log.debug("Operation log transaction advice does not wrap {}", method.getName());
        throw new IllegalStateException("OPERATION_LOG_TRANSACTION_ADVICE_ORDER_INVALID: " + method.getName());
    }

    private static OperationLog metadata(Method method, Class<?> type) {
        Method actual = actualMethod(method, type);
        OperationLog operation = AnnotatedElementUtils.findMergedAnnotation(actual, OperationLog.class);
        return operation != null ? operation : AnnotatedElementUtils.findMergedAnnotation(method, OperationLog.class);
    }

    private static Method actualMethod(Method method, Class<?> type) {
        return BridgeMethodResolver.getMostSpecificMethod(method, ClassUtils.getUserClass(type));
    }
}
