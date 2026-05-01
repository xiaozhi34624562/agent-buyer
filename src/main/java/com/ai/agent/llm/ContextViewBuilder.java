package com.ai.agent.llm;

import com.ai.agent.trajectory.TrajectoryReader;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class ContextViewBuilder {
    private final TrajectoryReader trajectoryReader;
    private final TranscriptPairValidator transcriptPairValidator;

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator
    ) {
        this.trajectoryReader = trajectoryReader;
        this.transcriptPairValidator = transcriptPairValidator;
    }

    public ProviderContextView build(String runId) {
        List<LlmMessage> rawMessages = trajectoryReader.loadMessages(runId);
        transcriptPairValidator.validate(rawMessages);

        ProviderContextView view = new ProviderContextView(rawMessages);
        transcriptPairValidator.validate(view.messages());
        return view;
    }
}
