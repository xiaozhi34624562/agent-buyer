package com.ai.agent.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public final class TokenEstimator {
    private static final int APPROX_CHARS_PER_TOKEN = 4;

    public int estimate(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return tokens(content).stream()
                .mapToInt(TokenEstimator::estimateSegment)
                .sum();
    }

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

    private static int estimateSegment(String token) {
        return Math.max(1, (token.length() + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN);
    }

    private static List<String> tokens(String content) {
        return Arrays.stream(content.trim().split("\\s+")).toList();
    }
}
