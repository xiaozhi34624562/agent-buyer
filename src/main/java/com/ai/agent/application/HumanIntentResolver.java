package com.ai.agent.application;

import com.ai.agent.trajectory.model.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class HumanIntentResolver {
    private static final Logger log = LoggerFactory.getLogger(HumanIntentResolver.class);
    private static final double MIN_DECISION_CONFIDENCE = 0.70d;
    private static final String DEFAULT_CONFIRMATION_QUESTION = "请明确回复确认继续执行，或回复放弃本次操作。";

    private final ConfirmationIntentService ruleClassifier;
    private final SemanticConfirmationIntentClassifier semanticClassifier;

    public HumanIntentResolver(
            ConfirmationIntentService ruleClassifier,
            SemanticConfirmationIntentClassifier semanticClassifier
    ) {
        this.ruleClassifier = ruleClassifier;
        this.semanticClassifier = semanticClassifier;
    }

    public ConfirmationDecision resolveConfirmation(
            String runId,
            String userId,
            RunContext runContext,
            String userMessage
    ) {
        ConfirmationIntentService.ConfirmationIntent ruleIntent = ruleClassifier.classify(userMessage);
        if (ruleIntent == ConfirmationIntentService.ConfirmationIntent.CONFIRM) {
            return ConfirmationDecision.confirm(1.0d, "rule");
        }
        if (ruleIntent == ConfirmationIntentService.ConfirmationIntent.REJECT) {
            return ConfirmationDecision.reject(1.0d, "rule");
        }

        try {
            ConfirmationDecision decision = semanticClassifier.classify(runId, userId, runContext, userMessage);
            if (decision == null) {
                return ConfirmationDecision.clarify(DEFAULT_CONFIRMATION_QUESTION, "llm_empty");
            }
            if (decision.intent() == ConfirmationIntent.CLARIFY) {
                return decision.withQuestion(defaultQuestion(decision.question()));
            }
            if (decision.confidence() < MIN_DECISION_CONFIDENCE) {
                return ConfirmationDecision.clarify(DEFAULT_CONFIRMATION_QUESTION, "llm_low_confidence");
            }
            return decision;
        } catch (RuntimeException e) {
            log.warn("semantic confirmation classifier failed runId={} error={}", runId, e.getMessage());
            return ConfirmationDecision.clarify(DEFAULT_CONFIRMATION_QUESTION, "llm_error");
        }
    }

    private String defaultQuestion(String question) {
        return question == null || question.isBlank() ? DEFAULT_CONFIRMATION_QUESTION : question;
    }

    public enum ConfirmationIntent {
        CONFIRM,
        REJECT,
        CLARIFY
    }

    public record ConfirmationDecision(
            ConfirmationIntent intent,
            double confidence,
            String question,
            String source
    ) {
        public static ConfirmationDecision confirm(double confidence, String source) {
            return new ConfirmationDecision(ConfirmationIntent.CONFIRM, confidence, null, source);
        }

        public static ConfirmationDecision reject(double confidence, String source) {
            return new ConfirmationDecision(ConfirmationIntent.REJECT, confidence, null, source);
        }

        public static ConfirmationDecision clarify(String question, String source) {
            return new ConfirmationDecision(ConfirmationIntent.CLARIFY, 1.0d, question, source);
        }

        public ConfirmationDecision withQuestion(String nextQuestion) {
            return new ConfirmationDecision(intent, confidence, nextQuestion, source);
        }
    }
}
