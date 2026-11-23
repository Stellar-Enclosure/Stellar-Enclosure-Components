package top.egon.cola.component.outbox.statemachine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;
import top.egon.cola.component.outbox.common.exception.OutboxStateMachineException;
import top.egon.cola.component.outbox.store.OutboxStatus;

import java.util.Set;

/** Creates a fresh, single-region in-memory graph for each technical transition. */
@Slf4j
@RequiredArgsConstructor
public class OutboxLifecycleStateMachineFactory {

    public static final String NEW_STATE = "__NEW__";

    private static final Set<String> STATES = Set.of(
            NEW_STATE,
            "PENDING",
            "PROCESSING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "DEAD"
    );

    static final String SAFE_CLAIM_HEADER = "safeClaim";

    private static final String ATTEMPT_COUNT_HEADER = "attemptCount";
    private static final String MAX_ATTEMPTS_HEADER = "maxAttempts";

    private static final Guard<String, OutboxLifecycleSignalEnum> RETRY_WITH_BUDGET = context -> {
        Object attemptCount = context.getMessageHeader(ATTEMPT_COUNT_HEADER);
        Object maxAttempts = context.getMessageHeader(MAX_ATTEMPTS_HEADER);
        return attemptCount instanceof Number attempt
                && maxAttempts instanceof Number maximum
                && attempt.intValue() < maximum.intValue();
    };

    private static final Guard<String, OutboxLifecycleSignalEnum> RETRY_EXHAUSTED = context -> {
        Object attemptCount = context.getMessageHeader(ATTEMPT_COUNT_HEADER);
        Object maxAttempts = context.getMessageHeader(MAX_ATTEMPTS_HEADER);
        return attemptCount instanceof Number attempt
                && maxAttempts instanceof Number maximum
                && attempt.intValue() >= maximum.intValue();
    };

    public StateMachine<String, OutboxLifecycleSignalEnum> create() {
        try {
            StateMachineBuilder.Builder<String, OutboxLifecycleSignalEnum> builder =
                    StateMachineBuilder.<String, OutboxLifecycleSignalEnum>builder();
            builder.configureConfiguration().withConfiguration().autoStartup(false);
            builder.configureStates().withStates().initial(NEW_STATE).states(STATES);

            var transitions = builder.configureTransitions();
            add(transitions, NEW_STATE, OutboxStatus.PENDING.getMessage(), OutboxLifecycleSignalEnum.ENQUEUE, null);
            for (OutboxStatus pending : Set.of(OutboxStatus.PENDING, OutboxStatus.RETRY_WAIT)) {
                add(transitions, pending.getMessage(), OutboxStatus.PROCESSING.getMessage(),
                        OutboxLifecycleSignalEnum.CLAIM, context ->
                                !Boolean.TRUE.equals(context.getMessageHeader(SAFE_CLAIM_HEADER))
                                        || RETRY_WITH_BUDGET.evaluate(context));
                add(transitions, pending.getMessage(), OutboxStatus.DEAD.getMessage(),
                        OutboxLifecycleSignalEnum.CLAIM, context ->
                                Boolean.TRUE.equals(context.getMessageHeader(SAFE_CLAIM_HEADER))
                                        && RETRY_EXHAUSTED.evaluate(context));
            }
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.PROCESSING.getMessage(),
                    OutboxLifecycleSignalEnum.RECLAIM, RETRY_WITH_BUDGET);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.DEAD.getMessage(),
                    OutboxLifecycleSignalEnum.RECLAIM, RETRY_EXHAUSTED);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.SUCCEEDED.getMessage(),
                    OutboxLifecycleSignalEnum.DELIVERY_SUCCEEDED, null);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.RETRY_WAIT.getMessage(),
                    OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE, RETRY_WITH_BUDGET);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.DEAD.getMessage(),
                    OutboxLifecycleSignalEnum.DELIVERY_RETRYABLE, RETRY_EXHAUSTED);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.RETRY_WAIT.getMessage(),
                    OutboxLifecycleSignalEnum.SCHEDULE_RETRY, null);
            add(transitions, OutboxStatus.PROCESSING.getMessage(), OutboxStatus.DEAD.getMessage(),
                    OutboxLifecycleSignalEnum.DELIVERY_PERMANENT, null);
            add(transitions, OutboxStatus.DEAD.getMessage(), OutboxStatus.RETRY_WAIT.getMessage(),
                    OutboxLifecycleSignalEnum.REDRIVE_EXHAUSTED, null);
            return builder.build();
        } catch (Exception exception) {
            throw new OutboxStateMachineException(
                    "OUTBOX_FSM_CONFIGURATION_FAILED",
                    false,
                    "Failed to build outbox lifecycle state machine",
                    exception
            );
        }
    }

    private static void add(
            StateMachineTransitionConfigurer<String, OutboxLifecycleSignalEnum> transitions,
            String source,
            String target,
            OutboxLifecycleSignalEnum signal,
            Guard<String, OutboxLifecycleSignalEnum> guard
    ) throws Exception {
        var transition = transitions.withExternal()
                .source(source)
                .target(target)
                .event(signal);
        if (guard != null) {
            transition.guard(guard);
        }
        transition.and();
    }
}
