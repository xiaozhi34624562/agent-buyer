package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSummaryGeneratorTest {

    @Test
    void generatesStrictJsonSummaryThroughConfiguredProvider() {
        AgentProperties properties = new AgentProperties();
        properties.getLlm().setProvider("qwen");
        FakeProviderAdapter qwen = new FakeProviderAdapter("qwen", """
                {"summaryText":"用户要取消订单","businessFacts":["用户查询了订单"],"toolFacts":["query_order returned order O1"],"openQuestions":[],"compactedMessageIds":["u1","t1"]}
                """.trim());
        ProviderSummaryGenerator generator = new ProviderSummaryGenerator(
                properties,
                new LlmProviderAdapterRegistry(List.of(new FakeProviderAdapter("deepseek", "{}"), qwen)),
                new ObjectMapper()
        );

        String summary = generator.generate("run-1", List.of(
                LlmMessage.user("u1", "取消昨天那个订单"),
                LlmMessage.tool("t1", "call-1", "{\"orderId\":\"O1\",\"status\":\"PAID\"}")
        ));

        assertThat(summary).contains("\"businessFacts\"");
        LlmChatRequest request = qwen.lastRequest();
        assertThat(request.runId()).isEqualTo("run-1");
        assertThat(request.model()).isEqualTo("qwen-model");
        assertThat(request.tools()).isEmpty();
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(properties.getContext().getSummaryMaxTokens());
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(request.messages().get(0).content()).contains("summaryText", "businessFacts", "toolFacts", "openQuestions");
        assertThat(request.messages().get(1).content()).contains("\"messageId\":\"u1\"", "\"messageId\":\"t1\"");
    }

    @Test
    void rejectsProviderSummaryThatReturnsToolCalls() {
        FakeProviderAdapter provider = new FakeProviderAdapter(
                "deepseek",
                "",
                List.of(new ToolCallMessage("call-1", "query_order", "{}"))
        );
        ProviderSummaryGenerator generator = new ProviderSummaryGenerator(
                new AgentProperties(),
                new LlmProviderAdapterRegistry(List.of(provider)),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> generator.generate("run-1", List.of(LlmMessage.user("u1", "hello"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary provider returned tool calls");
    }

    private static final class FakeProviderAdapter implements LlmProviderAdapter {
        private final String providerName;
        private final String content;
        private final List<ToolCallMessage> toolCalls;
        private final AtomicReference<LlmChatRequest> lastRequest = new AtomicReference<>();

        private FakeProviderAdapter(String providerName, String content) {
            this(providerName, content, List.of());
        }

        private FakeProviderAdapter(String providerName, String content, List<ToolCallMessage> toolCalls) {
            this.providerName = providerName;
            this.content = content;
            this.toolCalls = toolCalls;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return providerName + "-model";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            lastRequest.set(request);
            listener.onTextDelta(content);
            return new LlmStreamResult(content, toolCalls, FinishReason.STOP, null, "{}");
        }

        private LlmChatRequest lastRequest() {
            return lastRequest.get();
        }
    }
}
