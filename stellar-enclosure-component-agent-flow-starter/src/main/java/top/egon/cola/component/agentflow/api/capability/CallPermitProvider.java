package top.egon.cola.component.agentflow.api.capability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

@FunctionalInterface
public interface CallPermitProvider {

    @Valid @NotNull CallPermitVO requirePermit(@Valid @NotNull ContextScopeVO scope,
                                               @NotBlank String capabilityRef, @NotBlank String purpose);
}
