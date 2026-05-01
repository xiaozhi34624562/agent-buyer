package com.ai.agent.llm;

import com.ai.agent.domain.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderAdapterRegistryTest {
    @Test
    void resolvesProviderNameCaseInsensitively() {
        FakeProvider provider = new FakeProvider("deepseek");

        LlmProviderAdapterRegistry registry = new LlmProviderAdapterRegistry(List.of(provider));

        assertThat(registry.resolve("DeepSeek")).isSameAs(provider);
    }

    @Test
    void unknownProviderFailsClosedWithStableException() {
        LlmProviderAdapterRegistry registry = new LlmProviderAdapterRegistry(List.of(new FakeProvider("deepseek")));

        assertThatThrownBy(() -> registry.resolve("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown llm provider: qwen");
    }

    @Test
    void duplicateProviderNamesFailClosed() {
        assertThatThrownBy(() -> new LlmProviderAdapterRegistry(List.of(
                new FakeProvider("deepseek"),
                new FakeProvider("DeepSeek")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate llm provider: deepseek");
    }

    @Test
    void blankProviderNamesFailClosed() {
        assertThatThrownBy(() -> new LlmProviderAdapterRegistry(List.of(new FakeProvider(" "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("llm provider name must not be blank");
    }

    @Test
    void providerNamesWithSurroundingWhitespaceFailClosed() {
        assertThatThrownBy(() -> new LlmProviderAdapterRegistry(List.of(new FakeProvider(" deepseek "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("llm provider name must not contain surrounding whitespace");
    }

    private record FakeProvider(String providerName) implements LlmProviderAdapter {
        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }
}
