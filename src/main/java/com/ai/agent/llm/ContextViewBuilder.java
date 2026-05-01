package com.ai.agent.llm;

import com.ai.agent.trajectory.TrajectoryReader;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class ContextViewBuilder {
    private final TrajectoryReader trajectoryReader;
    private final TranscriptPairValidator transcriptPairValidator;
    private final LargeResultSpiller largeResultSpiller;

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller
    ) {
        this.trajectoryReader = trajectoryReader;
        this.transcriptPairValidator = transcriptPairValidator;
        this.largeResultSpiller = largeResultSpiller;
    }

    public ProviderContextView build(String runId) {
        List<LlmMessage> rawMessages = trajectoryReader.loadMessages(runId);
        transcriptPairValidator.validate(rawMessages);

        ProviderContextView view = new ProviderContextView(largeResultSpiller.spill(runId, rawMessages));
        transcriptPairValidator.validate(view.messages());
        return view;
    }
}
