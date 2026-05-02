package com.ai.agent.llm.context;

import com.ai.agent.llm.compact.LargeResultSpiller;
import com.ai.agent.llm.compact.MicroCompactor;
import com.ai.agent.llm.compact.SummaryCompactor;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.llm.summary.SummaryGenerationContext;
import com.ai.agent.llm.transcript.TranscriptPairValidator;
import com.ai.agent.skill.command.SkillCommandResolution;
import com.ai.agent.skill.command.SkillCommandResolver;
import com.ai.agent.todo.runtime.TodoReminderInjector;
import com.ai.agent.trajectory.model.ContextCompactionDraft;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 上下文视图构建器。
 * <p>
 * 从轨迹存储加载原始消息，依次应用大结果溢出、微压缩和概要压缩策略，
 * 构建最终发送给LLM提供商的上下文视图。
 * </p>
 */
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

    /**
     * 基础构造函数，仅包含核心依赖。
     *
     * @param trajectoryReader       轨迹读取器
     * @param transcriptPairValidator 消息配对验证器
     * @param largeResultSpiller     大结果溢出处理器
     * @param microCompactor         微压缩器
     * @param summaryCompactor       概要压缩器
     */
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

    /**
     * Spring自动注入构造函数。
     *
     * @param trajectoryReader          轨迹读取器
     * @param transcriptPairValidator   消息配对验证器
     * @param largeResultSpiller        大结果溢出处理器
     * @param microCompactor            微压缩器
     * @param summaryCompactor          概要压缩器
     * @param skillCommandResolverProvider 技能命令解析器提供者
     * @param todoReminderInjectorProvider 待办提醒注入器提供者
     * @param trajectoryWriterProvider  轨迹写入器提供者
     * @param objectMapper              JSON对象映射器
     */
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

    /**
     * 完整构造函数，包含所有依赖。
     *
     * @param trajectoryReader       轨迹读取器
     * @param transcriptPairValidator 消息配对验证器
     * @param largeResultSpiller     大结果溢出处理器
     * @param microCompactor         微压缩器
     * @param summaryCompactor       概要压缩器
     * @param skillCommandResolver   技能命令解析器
     * @param todoReminderInjector   待办提醒注入器
     * @param trajectoryWriter       轨迹写入器
     * @param objectMapper           JSON对象映射器
     */
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

    /**
     * 兼容构造函数，不包含待办提醒注入器。
     *
     * @param trajectoryReader       轨迹读取器
     * @param transcriptPairValidator 消息配对验证器
     * @param largeResultSpiller     大结果溢出处理器
     * @param microCompactor         微压缩器
     * @param summaryCompactor       概要压缩器
     * @param skillCommandResolver   技能命令解析器
     * @param trajectoryWriter       轨迹写入器
     * @param objectMapper           JSON对象映射器
     */
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

    /**
     * 构建上下文视图，使用默认参数。
     *
     * @param runId 运行ID
     * @return 提供者上下文视图
     */
    public ProviderContextView build(String runId) {
        return build(runId, 0, LlmCallObserver.NOOP);
    }

    /**
     * 构建上下文视图，指定轮次号和调用观察者。
     *
     * @param runId            运行ID
     * @param turnNo           轮次号
     * @param summaryCallObserver 摘要调用观察者
     * @return 提供者上下文视图
     */
    public ProviderContextView build(String runId, int turnNo, LlmCallObserver summaryCallObserver) {
        return build(runId, turnNo, null, summaryCallObserver);
    }

    /**
     * 构建上下文视图。
     * <p>
     * 加载原始消息，验证消息配对，注入技能命令和待办提醒，
     * 依次应用大结果溢出、微压缩和概要压缩策略，构建最终视图。
     * </p>
     *
     * @param runId            运行ID
     * @param turnNo           轮次号
     * @param runContext       运行上下文
     * @param summaryCallObserver 摘要调用观察者
     * @return 提供者上下文视图
     */
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

    /**
     * 如果压缩策略改变了消息列表，则记录压缩信息。
     *
     * @param strategy   压缩策略名称
     * @param before     压缩前的消息列表
     * @param after      压缩后的消息列表
     * @param compactions 压缩记录列表
     * @return 压缩后的消息列表
     */
    private List<LlmMessage> collectIfChanged(
            String strategy,
            List<LlmMessage> before,
            List<LlmMessage> after,
            List<ContextCompactionDraft> compactions
    ) {
        if (before.equals(after)) {
            return after;
        }
        List<String> compactedMessageIds = SUMMARY_COMPACT.equals(strategy)
                ? summaryCompactedMessageIds(after)
                : compactedMessageIds(before, after);
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

    /**
     * 获取被压缩的消息ID列表。
     * <p>
     * 比较压缩前后的消息，找出被删除或内容改变的消息ID。
     * </p>
     *
     * @param before 压缩前的消息列表
     * @param after  压缩后的消息列表
     * @return 被压缩的消息ID列表
     */
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

    /**
     * 从概要压缩后的消息中提取被压缩的消息ID列表。
     *
     * @param after 概要压缩后的消息列表
     * @return 被压缩的消息ID列表
     */
    private List<String> summaryCompactedMessageIds(List<LlmMessage> after) {
        for (LlmMessage message : after) {
            if (!Boolean.TRUE.equals(message.extras().get("compactSummary"))) {
                continue;
            }
            Object rawIds = message.extras().get("compactedMessageIds");
            if (rawIds instanceof List<?> values) {
                List<String> ids = new ArrayList<>();
                for (Object value : values) {
                    if (value != null) {
                        ids.add(value.toString());
                    }
                }
                return List.copyOf(ids);
            }
        }
        return List.of();
    }

    /**
     * 计算消息列表的总token数。
     *
     * @param messages 消息列表
     * @return 总token数
     */
    private int totalTokens(List<LlmMessage> messages) {
        return messages.stream()
                .map(LlmMessage::content)
                .mapToInt(tokenEstimator::estimate)
                .sum();
    }

    /**
     * 注入斜杠技能命令解析后的消息。
     *
     * @param runId      运行ID
     * @param turnNo     轮次号
     * @param rawMessages 原始消息列表
     * @return 注入技能消息后的消息列表
     */
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

    /**
     * 注入待办提醒消息。
     *
     * @param runId   运行ID
     * @param turnNo  轮次号
     * @param messages 消息列表
     * @return 注入待办提醒后的消息列表
     */
    private List<LlmMessage> injectTodoReminder(String runId, int turnNo, List<LlmMessage> messages) {
        if (todoReminderInjector == null) {
            return messages;
        }
        return todoReminderInjector.inject(runId, turnNo, messages);
    }

    /**
     * 写入技能注入事件到轨迹存储。
     *
     * @param runId      运行ID
     * @param turnNo     轮次号
     * @param resolution 技能命令解析结果
     */
    private void writeSkillEvents(String runId, int turnNo, SkillCommandResolution resolution) {
        if (trajectoryWriter == null) {
            return;
        }
        for (String skillName : resolution.skillNames()) {
            trajectoryWriter.writeAgentEvent(runId, "skill_slash_injected", skillEventPayload(turnNo, skillName, resolution.totalTokens()));
        }
    }

    /**
     * 构建技能事件payload的JSON字符串。
     *
     * @param turnNo     轮次号
     * @param skillName  技能名称
     * @param totalTokens 总token数
     * @return JSON payload字符串
     */
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
