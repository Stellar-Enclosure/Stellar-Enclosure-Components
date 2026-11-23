package top.egon.cola.component.common.mybatis.operationlog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Immutable identity and request snapshot supplied by the authenticated host. */
public record OperationLogContextDTO(
        @NotNull @Positive Long tenantId,
        @NotBlank @Pattern(regexp = "USER|SERVICE|SYSTEM") String actorType,
        @NotBlank @Size(max = 512) String actorId,
        @Size(max = 128) String actorName,
        @NotBlank @Pattern(regexp = "HTTP|RPC|MESSAGE|JOB") String triggerType,
        @Size(max = 16) String httpMethod,
        @Size(max = 2048) String requestUrl,
        @Size(max = 512) String routePattern,
        @Size(max = 1024) String handlerMethod,
        @Size(max = 64) String traceId,
        @Size(max = 128) String requestId) {
}
