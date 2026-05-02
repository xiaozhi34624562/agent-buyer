package com.ai.agent.application;

import com.ai.agent.application.HumanIntentResolver.ConfirmationDecision;
import com.ai.agent.application.HumanIntentResolver.ConfirmationIntent;
import com.ai.agent.trajectory.model.RunContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanIntentResolverTest {
    @Test
    void ruleConfirmShortCircuitsWithoutCallingLlm() {
        CountingSemanticClassifier classifier = new CountingSemanticClassifier(ConfirmationDecision.clarify("unused", "llm"));
        HumanIntentResolver resolver = new HumanIntentResolver(new ConfirmationIntentService(), classifier);

        ConfirmationDecision decision = resolver.resolveConfirmation(
                "run-1",
                "user-1",
                runContext("run-1"),
                "确认取消"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CONFIRM);
        assertThat(decision.source()).isEqualTo("rule");
        assertThat(classifier.calls).isZero();
    }

    @Test
    void ambiguousRuleFallsBackToLlmSemanticClassifier() {
        CountingSemanticClassifier classifier = new CountingSemanticClassifier(
                ConfirmationDecision.confirm(0.91, "llm")
        );
        HumanIntentResolver resolver = new HumanIntentResolver(new ConfirmationIntentService(), classifier);

        ConfirmationDecision decision = resolver.resolveConfirmation(
                "run-1",
                "user-1",
                runContext("run-1"),
                "听起来还行吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CONFIRM);
        assertThat(decision.confidence()).isEqualTo(0.91);
        assertThat(decision.source()).isEqualTo("llm");
        assertThat(classifier.calls).isEqualTo(1);
    }

    @Test
    void lowConfidenceLlmDecisionBecomesClarification() {
        CountingSemanticClassifier classifier = new CountingSemanticClassifier(
                ConfirmationDecision.confirm(0.42, "llm")
        );
        HumanIntentResolver resolver = new HumanIntentResolver(new ConfirmationIntentService(), classifier);

        ConfirmationDecision decision = resolver.resolveConfirmation(
                "run-1",
                "user-1",
                runContext("run-1"),
                "随便吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CLARIFY);
        assertThat(decision.question()).contains("请明确");
    }

    @Test
    void llmFailureFailsClosedAsClarification() {
        HumanIntentResolver resolver = new HumanIntentResolver(
                new ConfirmationIntentService(),
                (runId, userId, runContext, userMessage) -> {
                    throw new IllegalStateException("provider down");
                }
        );

        ConfirmationDecision decision = resolver.resolveConfirmation(
                "run-1",
                "user-1",
                runContext("run-1"),
                "行吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CLARIFY);
        assertThat(decision.question()).contains("请明确");
        assertThat(decision.source()).isEqualTo("llm_error");
    }

    private static RunContext runContext(String runId) {
        return new RunContext(
                runId,
                List.of("query_order", "cancel_order"),
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static final class CountingSemanticClassifier implements SemanticConfirmationIntentClassifier {
        private final ConfirmationDecision decision;
        private int calls;

        private CountingSemanticClassifier(ConfirmationDecision decision) {
            this.decision = decision;
        }

        @Override
        public ConfirmationDecision classify(String runId, String userId, RunContext runContext, String userMessage) {
            calls++;
            return decision;
        }
    }
}
