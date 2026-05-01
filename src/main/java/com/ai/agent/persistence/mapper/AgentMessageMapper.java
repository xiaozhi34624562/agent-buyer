package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentMessageMapper extends BaseMapper<AgentMessageEntity> {
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM agent_message WHERE run_id = #{runId}")
    Long nextSeq(@Param("runId") String runId);

    @Select("""
            SELECT message_id, run_id, seq, role, content, tool_use_id, tool_calls, extras, created_at
            FROM agent_message
            WHERE run_id = #{runId}
            ORDER BY seq ASC
            """)
    List<AgentMessageEntity> findByRunId(@Param("runId") String runId);
}
