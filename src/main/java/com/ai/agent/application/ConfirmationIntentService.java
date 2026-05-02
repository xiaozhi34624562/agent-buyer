package com.ai.agent.application;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 确认意图服务
 * <p>
 * 基于规则的确认意图分类器，通过关键词匹配快速判断用户回复的意图，
 * 支持中文和英文的确认、拒绝、模糊表达识别。
 * </p>
 */
@Service
public final class ConfirmationIntentService {
    private static final Set<String> EXACT_CONFIRMATIONS = Set.of(
            "yes", "y", "ok", "okay", "confirm", "proceed", "continue");
    private static final Set<String> EXACT_REJECTIONS = Set.of(
            "no", "n", "nope", "no thanks");

    /**
     * 分类用户回复的确认意图
     * <p>
     * 通过关键词匹配判断用户回复是否为确认、拒绝或模糊意图，
     * 支持中英文表达，对问句和条件表达返回模糊结果。
     * </p>
     *
     * @param content 用户回复内容
     * @return 确认意图分类结果
     */
    public ConfirmationIntent classify(String content) {
        if (content == null || content.isBlank()) {
            return ConfirmationIntent.AMBIGUOUS;
        }
        String normalized = content.toLowerCase(Locale.ROOT)
                .replace('，', ',')
                .replace('。', '.')
                .replace('？', '?')
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('＇', '\'')
                .replace('`', '\'')
                .trim()
                .replaceAll("\\s+", " ");
        normalized = stripTrailingPunctuation(normalized);

        if (isExplicitReject(normalized)) {
            return ConfirmationIntent.REJECT;
        }
        if (isInquiryOrConditional(normalized)) {
            return ConfirmationIntent.AMBIGUOUS;
        }
        if (isExplicitConfirm(normalized)) {
            return ConfirmationIntent.CONFIRM;
        }
        return ConfirmationIntent.AMBIGUOUS;
    }

    /**
     * 判断是否为明确的确认表达
     *
     * @param value 标准化后的用户回复
     * @return true表示为确认表达
     */
    private boolean isExplicitConfirm(String value) {
        if (hasNegativeConfirmationScope(value)) {
            return false;
        }
        return EXACT_CONFIRMATIONS.contains(value)
                || value.equals("确认")
                || value.equals("继续")
                || value.equals("可以")
                || value.equals("执行")
                || value.equals("取消吧")
                || value.equals("确认取消")
                || value.equals("确认取消订单")
                || value.equals("确认取消这个订单")
                || value.equals("确认取消这笔订单")
                || value.equals("请确认取消")
                || value.equals("请确认取消订单")
                || value.equals("请确认取消这个订单")
                || value.equals("请确认取消这笔订单")
                || value.equals("继续取消")
                || value.equals("可以取消")
                || value.equals("执行取消")
                || value.equals("cancel it")
                || value.equals("please cancel it")
                || value.equals("go ahead")
                || value.equals("please go ahead")
                || value.equals("confirm it")
                || value.equals("looks good")
                || value.equals("no problem, cancel it")
                || isContextualChineseConfirmation(value);
    }

    /**
     * 判断是否为上下文相关的中文确认表达
     * <p>
     * 检查是否包含"没问题/可以/同意"等确认词，同时包含"继续/处理/执行/取消"等动作词。
     * </p>
     *
     * @param value 标准化后的用户回复
     * @return true表示为上下文相关的确认表达
     */
    private boolean isContextualChineseConfirmation(String value) {
        return (value.contains("没问题") || value.contains("可以") || value.contains("同意"))
                && (value.contains("继续") || value.contains("处理") || value.contains("执行") || value.contains("取消"));
    }

    /**
     * 判断是否为明确的拒绝表达
     *
     * @param value 标准化后的用户回复
     * @return true表示为拒绝表达
     */
    private boolean isExplicitReject(String value) {
        return hasNegativeConfirmationScope(value)
                || EXACT_REJECTIONS.contains(value)
                || value.contains("不取消")
                || value.contains("不用取消")
                || value.contains("别取消")
                || value.contains("不要取消")
                || value.contains("算了")
                || value.contains("不要了")
                || value.contains("放弃")
                || value.contains("不可以")
                || value.contains("不要执行")
                || value.contains("别执行")
                || value.contains("不用执行")
                || value.contains("cancel that")
                || value.contains("do not cancel")
                || value.contains("don't cancel");
    }

    private boolean hasNegativeConfirmationScope(String value) {
        return value.contains("do not go ahead")
                || value.contains("don't go ahead")
                || value.contains("please don't go ahead")
                || value.contains("do not proceed")
                || value.contains("don't proceed")
                || value.contains("please don't proceed")
                || value.contains("do not continue")
                || value.contains("don't continue")
                || value.contains("please don't continue")
                || value.contains("do not confirm")
                || value.contains("don't confirm")
                || value.contains("please don't confirm")
                || value.contains("别继续")
                || value.contains("不要继续")
                || value.contains("不继续")
                || value.contains("别确认")
                || value.contains("不要确认")
                || value.contains("不确认")
                || value.contains("别取消吧")
                || value.contains("不要取消吧");
    }

    private boolean isInquiryOrConditional(String value) {
        return value.contains("?")
                || value.contains("吗")
                || value.contains("么")
                || value.contains("是不是")
                || value.contains("是否")
                || value.contains("能不能")
                || value.contains("可不可以")
                || value.contains("what happens")
                || value.contains("do i ")
                || value.contains("should i ")
                || value.contains("can i ")
                || value.contains("could i ")
                || value.contains("if i ")
                || value.contains("if we ")
                || value.contains("whether ");
    }

    private String stripTrailingPunctuation(String value) {
        return value.replaceAll("[,，.!！。]+$", "").trim();
    }

    public enum ConfirmationIntent {
        CONFIRM,
        REJECT,
        AMBIGUOUS
    }
}
