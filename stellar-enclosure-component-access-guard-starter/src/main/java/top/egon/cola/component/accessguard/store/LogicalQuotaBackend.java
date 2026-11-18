package top.egon.cola.component.accessguard.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Shared, fail-closed logical-call accounting, separate from local rate-limit backends. */
@FunctionalInterface
public interface LogicalQuotaBackend {

    LogicalQuotaDecisionVO acquire(@NotNull @Valid LogicalQuotaAcquireCommand command);
}
