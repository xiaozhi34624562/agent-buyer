package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {
    @Update("""
            UPDATE agent_run
            SET status = #{status},
                last_error = #{error},
                completed_at = CASE
                    WHEN #{status} IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
                    THEN CURRENT_TIMESTAMP(3)
                    ELSE completed_at
                END
            WHERE run_id = #{runId}
            """)
    int updateStatus(@Param("runId") String runId, @Param("status") String status, @Param("error") String error);

    @Update("""
            UPDATE agent_run
            SET status = #{nextStatus},
                last_error = #{error},
                completed_at = CASE
                    WHEN #{nextStatus} IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
                    THEN CURRENT_TIMESTAMP(3)
                    ELSE completed_at
                END
            WHERE run_id = #{runId}
              AND status = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("runId") String runId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("error") String error
    );

    @Update("UPDATE agent_run SET turn_no = turn_no + 1 WHERE run_id = #{runId}")
    int incrementTurn(@Param("runId") String runId);

    @Select("SELECT turn_no FROM agent_run WHERE run_id = #{runId}")
    Integer currentTurn(@Param("runId") String runId);

    @Select("SELECT user_id FROM agent_run WHERE run_id = #{runId}")
    String findUserId(@Param("runId") String runId);

    @Select("SELECT status FROM agent_run WHERE run_id = #{runId}")
    String findStatus(@Param("runId") String runId);

    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE parent_tool_call_id = #{parentToolCallId}
            LIMIT 1
            """)
    AgentRunEntity findByParentToolCallId(@Param("parentToolCallId") String parentToolCallId);

    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE status IN ('CREATED', 'RUNNING')
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRunEntity> findStartupRepairCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE status = 'WAITING_USER_CONFIRMATION'
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRunEntity> findExpiredConfirmationRuns(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE parent_run_id = #{parentRunId}
              AND parent_link_status = 'LIVE'
              AND status NOT IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
            ORDER BY started_at ASC
            """)
    List<AgentRunEntity> findLiveChildren(@Param("parentRunId") String parentRunId);

    @Update("""
            UPDATE agent_run
            SET parent_link_status = #{parentLinkStatus}
            WHERE run_id = #{childRunId}
            """)
    int updateParentLinkStatus(
            @Param("childRunId") String childRunId,
            @Param("parentLinkStatus") String parentLinkStatus
    );
}
