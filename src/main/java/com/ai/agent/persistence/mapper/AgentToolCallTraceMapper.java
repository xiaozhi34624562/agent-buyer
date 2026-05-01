package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentToolCallTraceMapper extends BaseMapper<AgentToolCallTraceEntity> {
    @Select("""
            SELECT tool_call_id, run_id, message_id, seq, tool_use_id, raw_tool_name, tool_name,
                   args_json, is_concurrent, precheck_failed, precheck_error_json, created_at
            FROM agent_tool_call_trace
            WHERE run_id = #{runId}
            ORDER BY seq ASC
            """)
    List<AgentToolCallTraceEntity> findByRunId(@Param("runId") String runId);
}
