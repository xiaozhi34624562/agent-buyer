package com.ai.agent.llm.provider.qwen;

import com.ai.agent.tool.core.ToolSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QwenCompatibilityProfileTest {
    private final QwenCompatibilityProfile profile = new QwenCompatibilityProfile(new ObjectMapper());

    @Test
    void convertsToolSchemaToOpenAiCompatibleFunctionTool() {
        ToolSchema schema = new ToolSchema(
                "query_order",
                "Query order status",
                """
                        {"type":"object","properties":{"orderId":{"type":"string"}},"required":["orderId"]}
                        """,
                true,
                true,
                Duration.ofSeconds(5),
                1024,
                List.of()
        );

        List<Map<String, Object>> tools = profile.toProviderTools(List.of(schema));

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst())
                .containsEntry("type", "function")
                .containsKey("function");
        assertThat(tools.getFirst().get("function"))
                .isEqualTo(Map.of(
                        "name", "query_order",
                        "description", "Query order status",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("orderId", Map.of("type", "string")),
                                "required", List.of("orderId")
                        )
                ));
    }
}
