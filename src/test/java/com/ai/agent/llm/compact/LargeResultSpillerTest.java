package com.ai.agent.llm.compact;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LargeResultSpillerTest {
    @Test
    void spillsLargeToolResultIntoProviderOnlyHeadTailView() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(12);
        properties.getContext().setLargeResultHeadTokens(3);
        properties.getContext().setLargeResultTailTokens(2);
        LargeResultSpiller spiller = new LargeResultSpiller(properties, new TokenEstimator());
        LlmMessage raw = LlmMessage.tool("m-tool", "call-1", tokenText(18));

        List<LlmMessage> spilled = spiller.spill("run-1", List.of(raw));

        assertThat(spilled).hasSize(1);
        assertThat(spilled.get(0)).isNotSameAs(raw);
        assertThat(spilled.get(0).content())
                .isEqualTo("t0 t1 t2\n<resultPath>trajectory://runs/run-1/tool-results/call-1/full</resultPath>\nt16 t17");
        assertThat(raw.content()).isEqualTo(tokenText(18));
    }

    @Test
    void leavesToolResultsAtThresholdUnchanged() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(12);
        LargeResultSpiller spiller = new LargeResultSpiller(properties, new TokenEstimator());
        LlmMessage raw = LlmMessage.tool("m-tool", "call-1", tokenText(12));

        List<LlmMessage> spilled = spiller.spill("run-1", List.of(raw));

        assertThat(spilled).containsExactly(raw);
    }

    @Test
    void spillsPrettyJsonContainingLongWhitespaceSeparatedValue() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(20);
        properties.getContext().setLargeResultHeadTokens(2);
        properties.getContext().setLargeResultTailTokens(2);
        LargeResultSpiller spiller = new LargeResultSpiller(properties, new TokenEstimator());
        String rawContent = "{\n  \"blob\": \"" + "x".repeat(200) + "\"\n}";
        LlmMessage raw = LlmMessage.tool("m-tool", "call-1", rawContent);

        List<LlmMessage> spilled = spiller.spill("run-1", List.of(raw));

        assertThat(new TokenEstimator().estimate(rawContent)).isGreaterThan(20);
        assertThat(spilled.get(0).content())
                .contains("<resultPath>trajectory://runs/run-1/tool-results/call-1/full</resultPath>");
        assertThat(raw.content()).isEqualTo(rawContent);
    }

    private static String tokenText(int tokenCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append('t').append(i);
        }
        return builder.toString();
    }
}
