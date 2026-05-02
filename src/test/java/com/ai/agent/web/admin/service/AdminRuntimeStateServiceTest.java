package com.ai.agent.web.admin.service;

import com.ai.agent.web.admin.dto.AdminRuntimeStateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminRuntimeStateServiceTest {

    @Autowired
    private AdminRuntimeStateService service;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.ai.agent.tool.runtime.redis.RedisKeys redisKeys;

    private static final String TEST_RUN_ID = "test-runtime-run-001";
    private static final String OTHER_RUN_ID = "other-runtime-run-002";
    private static final String SECRET_TOKEN = "secret-confirm-token-abc123";

    @BeforeEach
    void cleanRedis() {
        // Clean test keys
        Set<String> keys = redisTemplate.keys("agent:{run:test-runtime-run*}*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        keys = redisTemplate.keys("agent:{run:other-runtime-run*}*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // Remove from active runs
        redisTemplate.opsForSet().remove("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForSet().remove("agent:active-runs", OTHER_RUN_ID);
    }

    @Test
    @DisplayName("getRuntimeState should return activeRun=true when run is in active-runs set")
    void activeRun_true_whenInSet() {
        // Setup
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "status", "RUNNING");
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "userId", "demo-user");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.activeRun()).isTrue();
        assertThat(dto.runId()).isEqualTo(TEST_RUN_ID);
    }

    @Test
    @DisplayName("getRuntimeState should return activeRun=false when run not in active-runs set")
    void activeRun_false_whenNotInSet() {
        // Setup - add meta but NOT in active-runs
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "status", "SUCCEEDED");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.activeRun()).isFalse();
    }

    @Test
    @DisplayName("getRuntimeState should return entries for fixed keys only")
    void entries_fixedKeys_only() {
        // Setup
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "status", "RUNNING");
        redisTemplate.opsForZSet().add(redisKeys.queue(TEST_RUN_ID), "tool-1", 1000);
        redisTemplate.opsForSet().add(redisKeys.tools(TEST_RUN_ID), "tool-use-1");
        redisTemplate.opsForHash().put(redisKeys.leases(TEST_RUN_ID), "tool-use-1", "90000");
        redisTemplate.opsForHash().put(redisKeys.control(TEST_RUN_ID), "abort_requested", "true");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.entries()).isNotNull();
        assertThat(dto.entries().containsKey("meta")).isTrue();
        assertThat(dto.entries().containsKey("queue")).isTrue();
        assertThat(dto.entries().containsKey("tools")).isTrue();
        assertThat(dto.entries().containsKey("leases")).isTrue();
        assertThat(dto.entries().containsKey("control")).isTrue();
    }

    @Test
    @DisplayName("getRuntimeState should NOT return complete agent:active-runs set")
    void noCompleteActiveRunsSet() {
        // Setup - multiple runs in active-runs
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForSet().add("agent:active-runs", OTHER_RUN_ID);

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        // Should not have an entry for the complete active-runs set
        assertThat(dto.entries().containsKey("active-runs")).isFalse();
        // activeRun boolean is the only projection of active-runs
        assertThat(dto.activeRun()).isTrue();
    }

    @Test
    @DisplayName("getRuntimeState should NOT expose confirm-tokens")
    void noConfirmTokens_exposed() {
        // Setup - add confirm token in control (simulating real runtime)
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "status", "WAITING_USER_CONFIRMATION");
        // Put secret token that should NOT be returned
        redisTemplate.opsForHash().put(redisKeys.control(TEST_RUN_ID), "confirmToken", SECRET_TOKEN);

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.entries().containsKey("control")).isTrue();
        Map<String, String> control = (Map<String, String>) dto.entries().get("control");
        assertThat(control).doesNotContainKey("confirmToken");
        assertThat(control).doesNotContainValue(SECRET_TOKEN);
    }

    @Test
    @DisplayName("getRuntimeState should NOT leak data from other runId")
    void noOtherRunData_leaked() {
        // Setup - put sensitive data in OTHER run
        redisTemplate.opsForSet().add("agent:active-runs", OTHER_RUN_ID);
        redisTemplate.opsForHash().put(redisKeys.meta(OTHER_RUN_ID), "userId", "other-user");
        redisTemplate.opsForHash().put(redisKeys.control(OTHER_RUN_ID), "confirmToken", "other-secret-token");

        // Query TEST run
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForHash().put(redisKeys.meta(TEST_RUN_ID), "userId", "demo-user");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.runId()).isEqualTo(TEST_RUN_ID);
        Map<String, String> meta = (Map<String, String>) dto.entries().get("meta");
        assertThat(meta).doesNotContainEntry("userId", "other-user");
        assertThat(dto.entries()).doesNotContainKey("control-other"); // No cross-run keys
    }

    @Test
    @DisplayName("getRuntimeState should handle missing keys gracefully")
    void handleMissingKeys_gracefully() {
        // Setup - run in active-runs but no Redis keys
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.activeRun()).isTrue();
        assertThat(dto.entries()).isNotNull();
        // Missing keys should result in empty/null values, not exceptions
    }

    @Test
    @DisplayName("getRuntimeState should include children and todos entries")
    void includeChildrenAndTodos() {
        // Setup
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForSet().add(redisKeys.children(TEST_RUN_ID), "child-run-1");
        redisTemplate.opsForHash().put(redisKeys.todos(TEST_RUN_ID), "step-1", "PENDING");
        redisTemplate.opsForValue().set(redisKeys.todoReminder(TEST_RUN_ID), "remember to check order");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        assertThat(dto.entries().containsKey("children")).isTrue();
        assertThat(dto.entries().containsKey("todos")).isTrue();
        assertThat(dto.entries().containsKey("todo-reminder")).isTrue();
    }

    @Test
    @DisplayName("getRuntimeState should NOT expose raw llm-call-budget value")
    void budgetValue_redactedOrOmitted() {
        // Setup
        redisTemplate.opsForSet().add("agent:active-runs", TEST_RUN_ID);
        redisTemplate.opsForValue().set(redisKeys.llmCallBudget(TEST_RUN_ID), "50");

        AdminRuntimeStateDto dto = service.getRuntimeState(TEST_RUN_ID);

        // Budget can be shown as summary but not raw counter
        assertThat(dto.entries().containsKey("llm-call-budget")).isFalse();
        // Or if included, it should be a summary like "remaining" not raw count
    }
}