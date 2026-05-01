package com.ai.agent.subagent;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisChildRunRegistryIntegrationTest {
    @Autowired
    ChildRunRegistry registry;

    @Autowired
    RedisKeys keys;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void reserveRejectsSecondInFlightChildForSameParentRun() {
        String parentRunId = Ids.newId("parent_run");
        try {
            ReserveChildResult first = registry.reserve(command(parentRunId, Ids.newId("child_run"), 1));
            ReserveChildResult second = registry.reserve(command(parentRunId, Ids.newId("child_run"), 1));

            assertThat(first.accepted()).isTrue();
            assertThat(first.errorCode()).isNull();
            assertThat(second.accepted()).isFalse();
            assertThat(second.errorCode()).isEqualTo(SubAgentBudgetPolicy.SUBAGENT_BUDGET_EXCEEDED);
            assertThat(second.reason()).isEqualTo(ChildReserveRejectReason.MAX_CONCURRENT_PER_RUN);
            assertThat(registry.findActiveChildren(parentRunId))
                    .extracting(ChildRunRef::childRunId)
                    .containsExactly(first.childRunId());
        } finally {
            cleanup(parentRunId);
        }
    }

    @Test
    void reserveIsIdempotentForSameParentToolCallId() {
        String parentRunId = Ids.newId("parent_run");
        String parentToolCallId = Ids.newId("tc");
        try {
            ReserveChildResult first = registry.reserve(command(
                    parentRunId,
                    Ids.newId("child_run"),
                    parentToolCallId,
                    1
            ));
            ReserveChildResult retry = registry.reserve(command(
                    parentRunId,
                    Ids.newId("child_run"),
                    parentToolCallId,
                    1
            ));

            assertThat(first.accepted()).isTrue();
            assertThat(first.reused()).isFalse();
            assertThat(retry.accepted()).isTrue();
            assertThat(retry.reused()).isTrue();
            assertThat(retry.childRunId()).isEqualTo(first.childRunId());
            assertThat(registry.findActiveChildren(parentRunId)).hasSize(1);
        } finally {
            cleanup(parentRunId);
        }
    }

    @Test
    void releaseFreesInFlightButDoesNotReleaseLifetimeSpawnCap() {
        String parentRunId = Ids.newId("parent_run");
        try {
            ReserveChildResult first = registry.reserve(command(parentRunId, Ids.newId("child_run"), 1));
            assertThat(first.accepted()).isTrue();
            assertThat(registry.release(parentRunId, first.childRunId(), ChildReleaseReason.SUCCEEDED, ParentLinkStatus.LIVE))
                    .isTrue();

            ReserveChildResult second = registry.reserve(command(parentRunId, Ids.newId("child_run"), 2));
            assertThat(second.accepted()).isTrue();
            assertThat(registry.release(parentRunId, second.childRunId(), ChildReleaseReason.SUCCEEDED, ParentLinkStatus.LIVE))
                    .isTrue();

            ReserveChildResult third = registry.reserve(command(parentRunId, Ids.newId("child_run"), 3));
            assertThat(third.accepted()).isFalse();
            assertThat(third.errorCode()).isEqualTo(SubAgentBudgetPolicy.SUBAGENT_BUDGET_EXCEEDED);
            assertThat(third.reason()).isEqualTo(ChildReserveRejectReason.MAX_SPAWN_PER_RUN);
            assertThat(registry.findActiveChildren(parentRunId)).isEmpty();
        } finally {
            cleanup(parentRunId);
        }
    }

    @Test
    void secondReleaseDoesNotOverwriteTerminalChildLifecycle() {
        String parentRunId = Ids.newId("parent_run");
        try {
            ReserveChildResult first = registry.reserve(command(parentRunId, Ids.newId("child_run"), 1));
            assertThat(first.accepted()).isTrue();

            assertThat(registry.release(parentRunId, first.childRunId(), ChildReleaseReason.SUCCEEDED, ParentLinkStatus.LIVE))
                    .isTrue();
            assertThat(registry.release(
                    parentRunId,
                    first.childRunId(),
                    ChildReleaseReason.TIMEOUT,
                    ParentLinkStatus.DETACHED_BY_TIMEOUT
            )).isFalse();

            Object raw = redisTemplate.opsForHash().get(keys.children(parentRunId), "child:" + first.childRunId());
            ChildRunRef ref = objectMapper.readValue(raw.toString(), ChildRunRef.class);
            assertThat(ref.releaseReason()).isEqualTo(ChildReleaseReason.SUCCEEDED);
            assertThat(ref.parentLinkStatus()).isEqualTo(ParentLinkStatus.LIVE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            cleanup(parentRunId);
        }
    }

    @Test
    void concurrentReserveAllowsOnlyOneInFlightChild() throws Exception {
        String parentRunId = Ids.newId("parent_run");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReserveChildResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int turnNo = i + 1;
                futures.add(executor.submit(() -> {
                    start.await();
                    return registry.reserve(command(parentRunId, Ids.newId("child_run"), turnNo));
                }));
            }
            start.countDown();

            List<ReserveChildResult> results = new ArrayList<>();
            for (Future<ReserveChildResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(ReserveChildResult::accepted).hasSize(1);
            assertThat(registry.findActiveChildren(parentRunId)).hasSize(1);
        } finally {
            executor.shutdownNow();
            cleanup(parentRunId);
        }
    }

    @Test
    void perTurnSpawnBudgetIsCheckedAtomically() {
        AgentProperties customProperties = new AgentProperties();
        customProperties.setRedisKeyPrefix("agent_test");
        customProperties.getSubAgent().setMaxSpawnPerRun(5);
        customProperties.getSubAgent().setMaxConcurrentPerRun(5);
        customProperties.getSubAgent().setSpawnBudgetPerUserTurn(1);
        RedisKeys customKeys = new RedisKeys(customProperties);
        ChildRunRegistry customRegistry = new RedisChildRunRegistry(
                customProperties,
                customKeys,
                redisTemplate,
                objectMapper
        );
        String parentRunId = Ids.newId("parent_run");
        try {
            ReserveChildResult first = customRegistry.reserve(command(parentRunId, Ids.newId("child_run"), 7));
            ReserveChildResult second = customRegistry.reserve(command(parentRunId, Ids.newId("child_run"), 7));

            assertThat(first.accepted()).isTrue();
            assertThat(second.accepted()).isFalse();
            assertThat(second.reason()).isEqualTo(ChildReserveRejectReason.SPAWN_BUDGET_PER_USER_TURN);
        } finally {
            redisTemplate.delete(customKeys.children(parentRunId));
        }
    }

    private ReserveChildCommand command(String parentRunId, String childRunId, int turnNo) {
        return command(parentRunId, childRunId, Ids.newId("tc"), turnNo);
    }

    private ReserveChildCommand command(String parentRunId, String childRunId, String parentToolCallId, int turnNo) {
        return new ReserveChildCommand(
                parentRunId,
                childRunId,
                parentToolCallId,
                "explore",
                turnNo,
                Instant.now()
        );
    }

    private void cleanup(String parentRunId) {
        redisTemplate.delete(keys.children(parentRunId));
    }
}
