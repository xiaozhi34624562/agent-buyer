package com.ai.agent.application;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public final class ConfirmationIntentService {
    private static final Set<String> EXACT_CONFIRMATIONS = Set.of(
            "yes", "y", "ok", "okay", "confirm", "proceed", "continue");
    private static final Set<String> EXACT_REJECTIONS = Set.of(
            "no", "n", "nope", "no thanks");

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
                || value.equals("no problem, cancel it");
    }

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
