package top.egon.cola.component.common.mybatis.operationlog;

import java.util.Optional;

/** A named application projection exposes only allowed fields from a completed command. */
@FunctionalInterface
public interface OperationLogProjection {

    Optional<OperationLogEvent> project(Object[] arguments, Object result);
}
