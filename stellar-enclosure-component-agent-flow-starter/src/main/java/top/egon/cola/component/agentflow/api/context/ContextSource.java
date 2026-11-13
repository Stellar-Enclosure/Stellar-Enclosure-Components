package top.egon.cola.component.agentflow.api.context;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;

@FunctionalInterface
public interface ContextSource {

    @Valid @NotNull ContextFragmentResult resolve(@Valid @NotNull ContextQuery query,
                                                  @Valid @NotNull ContextScopeVO scope,
                                                  @Valid @NotNull CallPermitVO permit);
}
