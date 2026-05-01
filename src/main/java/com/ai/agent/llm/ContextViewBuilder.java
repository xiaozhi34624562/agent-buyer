package com.ai.agent.llm;

import com.ai.agent.trajectory.ContextCompactionDraft;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class ContextViewBuilder {
    private static final String LARGE_RESULT_SPILL = "LARGE_RESULT_SPILL";
    private static final String MICRO_COMPACT = "MICRO_COMPACT";
    private static final String SUMMARY_COMPACT = "SUMMARY_COMPACT";

    private final TrajectoryReader trajectoryReader;
    private final TranscriptPairValidator transcriptPairValidator;
    private final LargeResultSpiller largeResultSpiller;
    private final MicroCompactor microCompactor;
    private final SummaryCompactor summaryCompactor;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller,
            MicroCompactor microCompactor,
            SummaryCompactor summaryCompactor
    ) {
        this.trajectoryReader = trajectoryReader;
        this.transcriptPairValidator = transcriptPairValidator;
        this.largeResultSpiller = largeResultSpiller;
        this.microCompactor = microCompactor;
        this.summaryCompactor = summaryCompactor;
    }

    public ProviderContextView build(String runId) {
        return build(runId, 0, LlmCallObserver.NOOP);
    }

    public ProviderContextView build(String runId, int turnNo, LlmCallObserver summaryCallObserver) {
        return build(runId, turnNo, null, summaryCallObserver);
    }

    public ProviderContextView build(
            String runId,
            int turnNo,
            RunContext runContext,
            LlmCallObserver summaryCallObserver
    ) {
        List<LlmMessage> rawMessages = trajectoryReader.loadMessages(runId);
        transcriptPairValidator.validate(rawMessages);
        List<ContextCompactionDraft> compactions = new ArrayList<>();

        List<LlmMessage> providerMessages = collectIfChanged(
                LARGE_RESULT_SPILL,
                rawMessages,
                largeResultSpiller.spill(runId, rawMessages),
                compactions
        );
        providerMessages = collectIfChanged(
                MICRO_COMPACT,
                providerMessages,
                microCompactor.compact(providerMessages),
                compactions
        );
        providerMessages = collectIfChanged(
                SUMMARY_COMPACT,
                providerMessages,
                summaryCompactor.compact(
                        new SummaryGenerationContext(runId, turnNo, runContext, summaryCallObserver),
                        providerMessages
                ),
                compactions
        );
        ProviderContextView view = new ProviderContextView(providerMessages, compactions);
        transcriptPairValidator.validate(view.messages());
        return view;
    }

    private List<LlmMessage> collectIfChanged(
            String strategy,
            List<LlmMessage> before,
            List<LlmMessage> after,
            List<ContextCompactionDraft> compactions
    ) {
        if (before.equals(after)) {
            return after;
        }
        List<String> compactedMessageIds = compactedMessageIds(before, after);
        if (compactedMessageIds.isEmpty()) {
            return after;
        }
        compactions.add(new ContextCompactionDraft(
                strategy,
                totalTokens(before),
                totalTokens(after),
                compactedMessageIds
        ));
        return after;
    }

    private List<String> compactedMessageIds(List<LlmMessage> before, List<LlmMessage> after) {
        Map<String, LlmMessage> afterById = new LinkedHashMap<>();
        for (LlmMessage message : after) {
            if (message.messageId() != null) {
                afterById.putIfAbsent(message.messageId(), message);
            }
        }
        List<String> ids = new ArrayList<>();
        for (LlmMessage message : before) {
            String messageId = message.messageId();
            if (messageId == null) {
                continue;
            }
            LlmMessage afterMessage = afterById.get(messageId);
            if (afterMessage == null || !message.equals(afterMessage)) {
                ids.add(messageId);
            }
        }
        return List.copyOf(ids);
    }

    private int totalTokens(List<LlmMessage> messages) {
        return messages.stream()
                .map(LlmMessage::content)
                .mapToInt(tokenEstimator::estimate)
                .sum();
    }
}
