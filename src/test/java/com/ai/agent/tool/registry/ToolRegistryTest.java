package com.ai.agent.tool.registry;

import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    @Test
    void resolvesNamesByLowercasingAndRemovingSeparators() {
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("query_order")));

        assertThat(registry.resolve("QueryOrder").schema().name()).isEqualTo("query_order");
        assertThat(registry.resolve("query-order").schema().name()).isEqualTo("query_order");
        assertThat(registry.resolve("query order").schema().name()).isEqualTo("query_order");
    }

    @Test
    void rejectsCanonicalNameCollisionsAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                new StubTool("query_order"),
                new StubTool("query-order")
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical tool name collision");
    }

    private record StubTool(String name) implements Tool {
        @Override
        public ToolSchema schema() {
            return new ToolSchema(
                    name,
                    "stub",
                    "{}",
                    true,
                    true,
                    Duration.ofSeconds(5),
                    4096,
                    List.of()
            );
        }
    }
}
