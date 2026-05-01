package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Update("UPDATE agent_run SET turn_no = turn_no + 1 WHERE run_id = #{runId}")
    int incrementTurn(@Param("runId") String runId);

    @Select("SELECT turn_no FROM agent_run WHERE run_id = #{runId}")
    Integer currentTurn(@Param("runId") String runId);

    @Select("SELECT user_id FROM agent_run WHERE run_id = #{runId}")
    String findUserId(@Param("runId") String runId);

    @Select("SELECT status FROM agent_run WHERE run_id = #{runId}")
    String findStatus(@Param("runId") String runId);
}
