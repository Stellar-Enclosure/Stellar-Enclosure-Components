package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.Access;
import top.egon.cola.component.agentflow.api.memory.MemoryStore.MemoryAuthority;

import java.time.Clock;
import java.util.Objects;
import java.util.stream.Collectors;

@FunctionalInterface
public interface MemoryExtractionStrategy {

    @Valid @NotNull MemoryCandidateResult extract(@Valid @NotNull MemoryExtractionCommand command,
                                                  @Valid @NotNull MemoryScopeVO scope,
                                                  @Valid @NotNull CallPermitVO permit);

    /** Wrap a trusted registered strategy at the host integration boundary before accepting a caller command. */
    static MemoryExtractionStrategy guarded(Clock clock, Validator validator, MemoryExtractionStrategy delegate) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(delegate, "strategy");
        return (command, scope, permit) -> {
            var fixed = Objects.requireNonNull(command, "command").snapshot();
            validate(validator, fixed);
            validate(validator, scope);
            validate(validator, permit);
            var authority = Access.requireAuthority(permit);
            verifySources(fixed, scope, permit, authority, clock);

            var approvedRefs = fixed.getSourceFragments().stream()
                    .map(MemoryExtractionCommand.SourceFragmentVO::sourceRef).collect(Collectors.toUnmodifiableSet());
            MemoryCandidateResult result;
            try {
                result = delegate.extract(new MemoryExtractionCommand(fixed.getSourceFragments(),
                        fixed.getStrategyRef(), fixed.getStrategyVersion()), scope, permit).permittedProjection(approvedRefs);
                validate(validator, result);
            } catch (IllegalArgumentException | ConstraintViolationException | NullPointerException failure) {
                throw Access.denied("INVALID_OUTPUT");
            }
            verifySources(fixed, scope, permit, authority, clock);
            Access.requireCurrent(permit, scope.hash(), fixed.getStrategyRef(), MemoryStore.EXTRACT_PURPOSE, clock);
            return result;
        };
    }

    private static void verifySources(MemoryExtractionCommand command, MemoryScopeVO scope, CallPermitVO permit,
                                      MemoryAuthority authority, Clock clock) {
        Access.requireCurrent(permit, scope.hash(), command.getStrategyRef(), MemoryStore.EXTRACT_PURPOSE, clock);
        var decision = authority.authorizeExtraction(scope, command.getStrategyRef(), command.getStrategyVersion());
        if (decision == null) {
            throw Access.denied("MEMORY_SOURCE_DENIED");
        }
        for (var source : command.getSourceFragments()) {
            if (!decision.allowedSources().contains(source.evidence())) {
                throw Access.denied("MEMORY_SOURCE_DENIED");
            }
            source.requireExactContent();
        }
        Access.requireCurrent(permit, scope.hash(), command.getStrategyRef(), MemoryStore.EXTRACT_PURPOSE, clock);
    }

    private static <T> void validate(Validator validator, T input) {
        var violations = validator.validate(Objects.requireNonNull(input, "Memory extraction boundary"));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
