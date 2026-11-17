package top.egon.cola.component.agentflow.context;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;

/** Immutable token budget and isolation rendering policy for unified context assembly. */
public record ContextBudgetPolicyVO(
        @Positive int contextWindowTokens,
        @Positive int maxOutputTokens,
        @PositiveOrZero int safetyMarginTokens,
        @PositiveOrZero int minKeepHistoryTurns,
        boolean combineContextIntoFinalUserMessage) {

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 8192;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;

    public ContextBudgetPolicyVO {
        if (contextWindowTokens <= 0 || maxOutputTokens <= 0
                || safetyMarginTokens < 0 || minKeepHistoryTurns < 0) {
            throw new IllegalArgumentException("Invalid ContextBudgetPolicyVO bounds");
        }
        if (contextWindowTokens <= maxOutputTokens + safetyMarginTokens) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must exceed maxOutputTokens + safetyMarginTokens");
        }
    }

    public int availableInputBudget() {
        return contextWindowTokens - maxOutputTokens - safetyMarginTokens;
    }

    public static ContextBudgetPolicyVO fromProperties(AgentFlowProperties.ContextSettings settings) {
        return new ContextBudgetPolicyVO(
                DEFAULT_CONTEXT_WINDOW_TOKENS,
                DEFAULT_MAX_OUTPUT_TOKENS,
                settings.safetyMarginTokens(),
                settings.minKeepHistoryTurns(),
                Boolean.TRUE.equals(settings.combineContextIntoFinalUserMessage()));
    }
}
