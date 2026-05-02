package com.ai.agent.application;

import com.ai.agent.trajectory.model.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户意图解析器
 * <p>
 * 用于解析用户对确认请求的回复意图，结合规则匹配和语义分类两种方式，
 * 判断用户是确认、拒绝还是需要澄清，支持人机交互中的意图识别。
 * </p>
 */
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

    /**
     * 解析用户确认意图
     * <p>
     * 先使用规则分类器进行快速判断，若无法确定则调用语义分类器进行深度分析。
     * 当置信度低于阈值或分类失败时，返回需要澄清的结果。
     * </p>
     *
     * @param runId      运行ID
     * @param userId     用户ID
     * @param runContext 运行上下文
     * @param userMessage 用户回复消息
     * @return 确认决策结果，包含意图类型、置信度、澄清问题等
     */
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

    /**
     * 获取默认澄清问题
     * <p>
     * 当语义分类器返回的问题为空或空白时，使用默认问题替代。
     * </p>
     *
     * @param question 原始问题
     * @return 有效的问题字符串
     */
    private String defaultQuestion(String question) {
        return question == null || question.isBlank() ? DEFAULT_CONFIRMATION_QUESTION : question;
    }

    /**
     * 确认意图枚举
     * <p>
     * 表示用户对确认请求的三种可能回复意图。
     * </p>
     */
    public enum ConfirmationIntent {
        CONFIRM,
        REJECT,
        CLARIFY
    }

    /**
     * 确认决策结果
     * <p>
     * 包含意图类型、置信度分数、澄清问题和决策来源。
     * </p>
     */
    public record ConfirmationDecision(
            ConfirmationIntent intent,
            double confidence,
            String question,
            String source
    ) {
        /**
         * 创建确认决策
         *
         * @param confidence 置信度分数
         * @param source     决策来源标识
         * @return 确认决策对象
         */
        public static ConfirmationDecision confirm(double confidence, String source) {
            return new ConfirmationDecision(ConfirmationIntent.CONFIRM, confidence, null, source);
        }

        /**
         * 创建拒绝决策
         *
         * @param confidence 置信度分数
         * @param source     决策来源标识
         * @return 拒绝决策对象
         */
        public static ConfirmationDecision reject(double confidence, String source) {
            return new ConfirmationDecision(ConfirmationIntent.REJECT, confidence, null, source);
        }

        /**
         * 创建澄清决策
         *
         * @param question 澄清问题
         * @param source   决策来源标识
         * @return 澄清决策对象
         */
        public static ConfirmationDecision clarify(String question, String source) {
            return new ConfirmationDecision(ConfirmationIntent.CLARIFY, 1.0d, question, source);
        }

        /**
         * 更新澄清问题
         *
         * @param nextQuestion 新的澄清问题
         * @return 更新问题后的决策对象
         */
        public ConfirmationDecision withQuestion(String nextQuestion) {
            return new ConfirmationDecision(intent, confidence, nextQuestion, source);
        }
    }
}
