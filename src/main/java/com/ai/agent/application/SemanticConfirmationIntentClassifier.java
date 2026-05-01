package com.ai.agent.application;

import com.ai.agent.application.HumanIntentResolver.ConfirmationDecision;
import com.ai.agent.trajectory.model.RunContext;

public interface SemanticConfirmationIntentClassifier {
    ConfirmationDecision classify(String runId, String userId, RunContext runContext, String userMessage);
}
