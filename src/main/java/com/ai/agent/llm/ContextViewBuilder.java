package com.ai.agent.llm;

import com.ai.agent.trajectory.ContextCompactionDraft;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryWriter;
import com.ai.agent.skill.SkillCommandResolution;
import com.ai.agent.skill.SkillCommandResolver;
import com.ai.agent.todo.TodoReminderInjector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SkillCommandResolver skillCommandResolver;
    private final TodoReminderInjector todoReminderInjector;
    private final TrajectoryWriter trajectoryWriter;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller,
            MicroCompactor microCompactor,
            SummaryCompactor summaryCompactor
    ) {
        this(
                trajectoryReader,
                transcriptPairValidator,
                largeResultSpiller,
                microCompactor,
                summaryCompactor,
                (SkillCommandResolver) null,
                null,
                null,
                null
        );
    }

    @Autowired
    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller,
            MicroCompactor microCompactor,
            SummaryCompactor summaryCompactor,
            ObjectProvider<SkillCommandResolver> skillCommandResolverProvider,
            ObjectProvider<TodoReminderInjector> todoReminderInjectorProvider,
            ObjectProvider<TrajectoryWriter> trajectoryWriterProvider,
            ObjectMapper objectMapper
    ) {
        this(
                trajectoryReader,
                transcriptPairValidator,
                largeResultSpiller,
                microCompactor,
                summaryCompactor,
                skillCommandResolverProvider.getIfAvailable(),
                todoReminderInjectorProvider.getIfAvailable(),
                trajectoryWriterProvider.getIfAvailable(),
                objectMapper
        );
    }

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller,
            MicroCompactor microCompactor,
            SummaryCompactor summaryCompactor,
            SkillCommandResolver skillCommandResolver,
            TodoReminderInjector todoReminderInjector,
            TrajectoryWriter trajectoryWriter,
            ObjectMapper objectMapper
    ) {
        this.trajectoryReader = trajectoryReader;
        this.transcriptPairValidator = transcriptPairValidator;
        this.largeResultSpiller = largeResultSpiller;
        this.microCompactor = microCompactor;
        this.summaryCompactor = summaryCompactor;
        this.skillCommandResolver = skillCommandResolver;
        this.todoReminderInjector = todoReminderInjector;
        this.trajectoryWriter = trajectoryWriter;
        this.objectMapper = objectMapper;
    }

    public ContextViewBuilder(
            TrajectoryReader trajectoryReader,
            TranscriptPairValidator transcriptPairValidator,
            LargeResultSpiller largeResultSpiller,
            MicroCompactor microCompactor,
            SummaryCompactor summaryCompactor,
            SkillCommandResolver skillCommandResolver,
            TrajectoryWriter trajectoryWriter,
            ObjectMapper objectMapper
    ) {
        this(
                trajectoryReader,
                transcriptPairValidator,
                largeResultSpiller,
                microCompactor,
                summaryCompactor,
                skillCommandResolver,
                null,
                trajectoryWriter,
                objectMapper
        );
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
        List<LlmMessage> workingMessages = injectTodoReminder(runId, turnNo, injectSlashSkills(runId, turnNo, rawMessages));

        List<LlmMessage> providerMessages = collectIfChanged(
                LARGE_RESULT_SPILL,
                workingMessages,
                largeResultSpiller.spill(runId, workingMessages),
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

    private List<LlmMessage> injectSlashSkills(String runId, int turnNo, List<LlmMessage> rawMessages) {
        if (skillCommandResolver == null) {
            return rawMessages;
        }
        SkillCommandResolution resolution = skillCommandResolver.resolve(rawMessages);
        if (resolution.messages().isEmpty()) {
            return rawMessages;
        }
        List<LlmMessage> injected = new ArrayList<>(rawMessages.size() + resolution.messages().size());
        injected.addAll(rawMessages);
        injected.addAll(resolution.messages());
        writeSkillEvents(runId, turnNo, resolution);
        return List.copyOf(injected);
    }

    private List<LlmMessage> injectTodoReminder(String runId, int turnNo, List<LlmMessage> messages) {
        if (todoReminderInjector == null) {
            return messages;
        }
        return todoReminderInjector.inject(runId, turnNo, messages);
    }

    private void writeSkillEvents(String runId, int turnNo, SkillCommandResolution resolution) {
        if (trajectoryWriter == null) {
            return;
        }
        for (String skillName : resolution.skillNames()) {
            trajectoryWriter.writeAgentEvent(runId, "skill_slash_injected", skillEventPayload(turnNo, skillName, resolution.totalTokens()));
        }
    }

    private String skillEventPayload(int turnNo, String skillName, int totalTokens) {
        if (objectMapper == null) {
            return "{\"skillName\":\"" + skillName + "\"}";
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "turnNo", turnNo,
                    "skillName", skillName,
                    "totalTokens", totalTokens
            ));
        } catch (JsonProcessingException e) {
            return "{\"skillName\":\"" + skillName + "\"}";
        }
    }
}
