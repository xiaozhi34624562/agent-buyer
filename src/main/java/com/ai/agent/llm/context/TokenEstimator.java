package com.ai.agent.llm.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Token估算器。
 * <p>
 * 提供基于字符数的近似token估算，用于上下文压缩策略中的token计数。
 * 采用每4个字符约等于1个token的简化估算规则。
 * </p>
 */
@Component
public final class TokenEstimator {
    private static final int APPROX_CHARS_PER_TOKEN = 4;

    /**
     * 估算内容的token数量。
     * <p>
     * 基于每4个字符约等于1个token的规则进行估算。
     * </p>
     *
     * @param content 待估算的内容字符串
     * @return 估算的token数量
     */
    public int estimate(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return tokens(content).stream()
                .mapToInt(TokenEstimator::estimateSegment)
                .sum();
    }

    /**
     * 提取内容的头部指定token数的内容。
     *
     * @param content   内容字符串
     * @param tokenCount 要提取的token数量
     * @return 头部内容字符串
     */
    public String head(String content, int tokenCount) {
        if (content == null || content.isBlank() || tokenCount <= 0) {
            return "";
        }
        List<String> headTokens = new ArrayList<>();
        int remaining = tokenCount;
        for (String token : tokens(content)) {
            if (remaining <= 0) {
                break;
            }
            int tokenEstimate = estimateSegment(token);
            if (tokenEstimate <= remaining) {
                headTokens.add(token);
                remaining -= tokenEstimate;
            } else {
                headTokens.add(token.substring(0, Math.min(token.length(), remaining * APPROX_CHARS_PER_TOKEN)));
                break;
            }
        }
        return String.join(" ", headTokens);
    }

    /**
     * 提取内容的尾部指定token数的内容。
     *
     * @param content   内容字符串
     * @param tokenCount 要提取的token数量
     * @return 尾部内容字符串
     */
    public String tail(String content, int tokenCount) {
        if (content == null || content.isBlank() || tokenCount <= 0) {
            return "";
        }
        List<String> sourceTokens = tokens(content);
        List<String> tailTokens = new ArrayList<>();
        int remaining = tokenCount;
        for (int i = sourceTokens.size() - 1; i >= 0; i--) {
            if (remaining <= 0) {
                break;
            }
            String token = sourceTokens.get(i);
            int tokenEstimate = estimateSegment(token);
            if (tokenEstimate <= remaining) {
                tailTokens.add(token);
                remaining -= tokenEstimate;
            } else {
                int charCount = remaining * APPROX_CHARS_PER_TOKEN;
                tailTokens.add(token.substring(Math.max(0, token.length() - charCount)));
                break;
            }
        }
        Collections.reverse(tailTokens);
        return String.join(" ", tailTokens);
    }

    /**
     * 估算单个文本段的token数量。
     *
     * @param token 文本段
     * @return 估算的token数量
     */
    private static int estimateSegment(String token) {
        return Math.max(1, (token.length() + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN);
    }

    /**
     * 将内容分割为token列表。
     *
     * @param content 内容字符串
     * @return token列表
     */
    private static List<String> tokens(String content) {
        return Arrays.stream(content.trim().split("\\s+")).toList();
    }
}
