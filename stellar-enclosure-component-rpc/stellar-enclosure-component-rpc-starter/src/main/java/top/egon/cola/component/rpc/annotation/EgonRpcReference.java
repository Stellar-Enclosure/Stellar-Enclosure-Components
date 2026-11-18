package top.egon.cola.component.rpc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Injects a native RPC contract through one deployment-approved Service binding. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EgonRpcReference {
    /** Binding key resolved once from trusted deployment configuration. */
    String bindingId() default "";
    /** Positive values can only narrow the process and contract deadlines. */
    long timeoutMs() default -1;
    /** Optional consistency pin; it cannot replace the native contract group. */
    String group() default "";
    /** Optional consistency pin; it cannot replace the native contract version. */
    String version() default "";
    /** Zero additional attempts; nonzero values require the later explicit approval contract. */
    int retries() default 0;
}
