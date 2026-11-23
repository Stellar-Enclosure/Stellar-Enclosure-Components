package top.egon.cola.component.common.mybatis.operationlog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    String actionCode();

    String resourceType();

    /** The exact name of an application OperationLogProjection bean. */
    String projector();
}
