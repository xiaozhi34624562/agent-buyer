package com.ai.agent.llm;

import com.ai.agent.trajectory.TrajectoryReader;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class ContextViewBuilder {
    private final TrajectoryReader trajectoryReader;
    private final TranscriptPairValidator transcriptPairValidator;
    private final LargeResultSpiller largeResultSpiller;
    private final MicroCompactor microCompactor;
    private final SummaryCompactor summaryCompactor;

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
        List<LlmMessage> rawMessages = trajectoryReader.loadMessages(runId);
        transcriptPairValidator.validate(rawMessages);

        List<LlmMessage> providerMessages = largeResultSpiller.spill(runId, rawMessages);
        providerMessages = microCompactor.compact(providerMessages);
        providerMessages = summaryCompactor.compact(runId, providerMessages);
        ProviderContextView view = new ProviderContextView(providerMessages);
        transcriptPairValidator.validate(view.messages());
        return view;
    }
}
