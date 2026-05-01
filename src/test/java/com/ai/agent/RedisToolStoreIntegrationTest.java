package com.ai.agent;

import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.CancelReason;
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
            ToolCall safeA = call(runId, 1, "query_order", true, true);
            ToolCall safeB = call(runId, 2, "query_order", true, true);
            ToolCall unsafeC = call(runId, 3, "cancel_order", false, false);
            ToolCall safeD = call(runId, 4, "query_order", true, true);

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
            assertThat(store.activeRunIds()).doesNotContain(runId);
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

    @Test
    void expiredNonIdempotentLeaseBecomesSyntheticCancelledAndUnblocksLaterCalls() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall unsafe = call(runId, 1, "cancel_order", false, false);
            ToolCall safe = call(runId, 2, "query_order", true, true);

            assertThat(store.ingestWaiting(runId, unsafe)).isTrue();
            assertThat(store.ingestWaiting(runId, safe)).isTrue();

            StartedTool startedUnsafe = store.schedule(runId).getFirst();
            List<ToolTerminal> expired = store.reapExpiredLeases(runId, startedUnsafe.leaseUntil() + 1);

            assertThat(expired).singleElement()
                    .satisfies(terminal -> {
                        assertThat(terminal.toolCallId()).isEqualTo(unsafe.toolCallId());
                        assertThat(terminal.status()).isEqualTo(ToolStatus.CANCELLED);
                        assertThat(terminal.cancelReason()).isEqualTo(CancelReason.TOOL_TIMEOUT);
                        assertThat(terminal.synthetic()).isTrue();
                    });
            assertThat(store.terminal(runId, unsafe.toolCallId())).get()
                    .extracting(ToolTerminal::status)
                    .isEqualTo(ToolStatus.CANCELLED);

            List<StartedTool> next = store.schedule(runId);
            assertThat(next).extracting(started -> started.call().toolCallId())
                    .containsExactly(safe.toolCallId());
        } finally {
            cleanup(runId);
        }
    }

    @Test
    void expiredIdempotentLeaseIsRequeuedForRetry() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall safe = call(runId, 1, "query_order", true, true);

            assertThat(store.ingestWaiting(runId, safe)).isTrue();
            StartedTool first = store.schedule(runId).getFirst();

            assertThat(store.reapExpiredLeases(runId, first.leaseUntil() + 1)).isEmpty();
            assertThat(store.terminal(runId, safe.toolCallId())).isEmpty();

            StartedTool retry = store.schedule(runId).getFirst();
            assertThat(retry.call().toolCallId()).isEqualTo(safe.toolCallId());
            assertThat(retry.attempt()).isEqualTo(first.attempt() + 1);
            assertThat(retry.leaseToken()).isNotEqualTo(first.leaseToken());
        } finally {
            cleanup(runId);
        }
    }

    @Test
    void duplicateToolUseIdWrongLeaseAndAbortAreHandledByRedisStateMachine() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall first = call(runId, 1, "cancel_order", false, false);
            ToolCall duplicate = new ToolCall(
                    runId,
                    Ids.newId("tc"),
                    2,
                    first.toolUseId(),
                    "cancel_order",
                    "cancel_order",
                    "{}",
                    false,
                    false,
                    false,
                    null
            );
            ToolCall waiting = call(runId, 3, "query_order", true, true);

            assertThat(store.ingestWaiting(runId, first)).isTrue();
            assertThat(store.ingestWaiting(runId, duplicate)).isFalse();
            assertThat(store.ingestWaiting(runId, waiting)).isTrue();

            StartedTool running = store.schedule(runId).getFirst();
            StartedTool wrongLease = new StartedTool(running.call(), running.attempt(), running.leaseToken() + "-wrong", running.leaseUntil(), running.workerId());
            assertThat(store.complete(wrongLease, ToolTerminal.succeeded(running.call().toolCallId(), "{}"))).isFalse();

            List<ToolTerminal> cancelled = store.cancelWaiting(runId, CancelReason.RUN_ABORTED);
            assertThat(cancelled).extracting(ToolTerminal::toolCallId).contains(waiting.toolCallId());
            assertThat(store.terminal(runId, waiting.toolCallId())).get()
                    .extracting(ToolTerminal::status)
                    .isEqualTo(ToolStatus.CANCELLED);

            assertThat(store.complete(running, ToolTerminal.succeeded(running.call().toolCallId(), "{}"))).isTrue();
            assertThat(store.activeRunIds()).doesNotContain(runId);
        } finally {
            cleanup(runId);
        }
    }

    @Test
    void abortCancelsWaitingButLetsRunningNonIdempotentToolRecordActualResult() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall runningCall = call(runId, 1, "cancel_order", false, false);
            ToolCall waitingCall = call(runId, 2, "query_order", true, true);

            assertThat(store.ingestWaiting(runId, runningCall)).isTrue();
            assertThat(store.ingestWaiting(runId, waitingCall)).isTrue();
            StartedTool running = store.schedule(runId).getFirst();

            List<ToolTerminal> cancelled = store.abort(runId, "user_abort");

            assertThat(store.abortRequested(runId)).isTrue();
            assertThat(cancelled).extracting(ToolTerminal::toolCallId)
                    .containsExactly(waitingCall.toolCallId());
            assertThat(store.schedule(runId)).isEmpty();
            assertThat(store.complete(running, ToolTerminal.succeeded(running.call().toolCallId(), "{}"))).isTrue();
            assertThat(store.terminal(runId, runningCall.toolCallId())).get()
                    .satisfies(terminal -> {
                        assertThat(terminal.status()).isEqualTo(ToolStatus.SUCCEEDED);
                        assertThat(terminal.synthetic()).isFalse();
                    });
        } finally {
            cleanup(runId);
        }
    }

    @Test
    void abortCancelsRunningIdempotentToolAndRejectsLateSuccessComplete() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall runningCall = call(runId, 1, "query_order", true, true);

            assertThat(store.ingestWaiting(runId, runningCall)).isTrue();
            StartedTool running = store.schedule(runId).getFirst();

            List<ToolTerminal> cancelled = store.abort(runId, "user_abort");

            assertThat(cancelled).extracting(ToolTerminal::toolCallId)
                    .containsExactly(runningCall.toolCallId());
            assertThat(store.complete(running, ToolTerminal.succeeded(running.call().toolCallId(), "{}"))).isFalse();
            assertThat(store.terminal(runId, runningCall.toolCallId())).get()
                    .satisfies(terminal -> {
                        assertThat(terminal.status()).isEqualTo(ToolStatus.CANCELLED);
                        assertThat(terminal.cancelReason()).isEqualTo(CancelReason.USER_ABORT);
                        assertThat(terminal.synthetic()).isTrue();
                    });
        } finally {
            cleanup(runId);
        }
    }

    @Test
    void ingestAfterAbortCreatesSyntheticTerminalInsteadOfWaitingForever() {
        String runId = Ids.newId("test_run");
        try {
            ToolCall postAbort = call(runId, 1, "query_order", true, true);

            assertThat(store.abort(runId, "user_abort")).isEmpty();
            assertThat(store.ingestWaiting(runId, postAbort)).isTrue();

            assertThat(store.schedule(runId)).isEmpty();
            assertThat(store.terminal(runId, postAbort.toolCallId())).get()
                    .satisfies(terminal -> {
                        assertThat(terminal.status()).isEqualTo(ToolStatus.CANCELLED);
                        assertThat(terminal.cancelReason()).isEqualTo(CancelReason.RUN_ABORTED);
                        assertThat(terminal.synthetic()).isTrue();
                    });
        } finally {
            cleanup(runId);
        }
    }

    private ToolCall call(String runId, long seq, String toolName, boolean concurrent, boolean idempotent) {
        return new ToolCall(
                runId,
                Ids.newId("tc"),
                seq,
                Ids.newId("call"),
                toolName,
                toolName,
                "{}",
                concurrent,
                idempotent,
                false,
                null
        );
    }

    private void cleanup(String runId) {
        redisTemplate.delete(List.of(
                keys.meta(runId),
                keys.queue(runId),
                keys.tools(runId),
                keys.toolUseIds(runId),
                keys.leases(runId)
        ));
        redisTemplate.opsForSet().remove(keys.activeRuns(), runId);
    }
}
