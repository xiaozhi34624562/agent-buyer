package com.ai.agent;

import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisToolStoreIntegrationTest {
    @Autowired
    RedisToolStore store;

    @Autowired
    RedisKeys keys;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void safeCallsStartTogetherButDoNotCrossUnsafeBarrier() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall safeA = call(runId, 1, "query_order", true);
            ToolCall safeB = call(runId, 2, "query_order", true);
            ToolCall unsafeC = call(runId, 3, "cancel_order", false);
            ToolCall safeD = call(runId, 4, "query_order", true);

            assertThat(store.ingestWaiting(runId, safeA)).isTrue();
            assertThat(store.ingestWaiting(runId, safeB)).isTrue();
            assertThat(store.ingestWaiting(runId, unsafeC)).isTrue();
            assertThat(store.ingestWaiting(runId, safeD)).isTrue();

            List<StartedTool> firstBatch = store.schedule(runId);
            assertThat(firstBatch).extracting(started -> started.call().toolCallId())
                    .containsExactly(safeA.toolCallId(), safeB.toolCallId());

            assertThat(store.schedule(runId)).isEmpty();
            firstBatch.forEach(started -> store.complete(started, ToolTerminal.succeeded(started.call().toolCallId(), "{}")));

            List<StartedTool> secondBatch = store.schedule(runId);
            assertThat(secondBatch).extracting(started -> started.call().toolCallId())
                    .containsExactly(unsafeC.toolCallId());

            assertThat(store.schedule(runId)).isEmpty();
            store.complete(secondBatch.getFirst(), ToolTerminal.succeeded(unsafeC.toolCallId(), "{}"));

            List<StartedTool> thirdBatch = store.schedule(runId);
            assertThat(thirdBatch).extracting(started -> started.call().toolCallId())
                    .containsExactly(safeD.toolCallId());
            store.complete(thirdBatch.getFirst(), ToolTerminal.succeeded(safeD.toolCallId(), "{}"));

            assertThat(store.terminal(runId, safeD.toolCallId())).get()
                    .extracting(ToolTerminal::status)
                    .isEqualTo(ToolStatus.SUCCEEDED);
        } finally {
            redisTemplate.delete(List.of(
                    keys.meta(runId),
                    keys.queue(runId),
                    keys.tools(runId),
                    keys.toolUseIds(runId),
                    keys.leases(runId)
            ));
        }
    }

    private ToolCall call(String runId, long seq, String toolName, boolean concurrent) {
        return new ToolCall(
                runId,
                Ids.newId("tc"),
                seq,
                Ids.newId("call"),
                toolName,
                toolName,
                "{}",
                concurrent,
                false,
                null
        );
    }
}
