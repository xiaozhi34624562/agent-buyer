#!/usr/bin/env bash
set -euo pipefail

mvn -Dtest=com.ai.agent.llm.QwenProviderAdapterTest,\
com.ai.agent.llm.QwenCompatibilityProfileTest,\
com.ai.agent.api.LlmAttemptServiceTest,\
com.ai.agent.api.V20ProviderSelectionIntegrationTest,\
com.ai.agent.llm.ContextViewBuilderTest,\
com.ai.agent.api.AgentTurnOrchestratorBudgetTest,\
com.ai.agent.api.RunStateMachineTest test
