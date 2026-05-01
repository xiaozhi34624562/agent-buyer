package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentToolCallTraceMapper extends BaseMapper<AgentToolCallTraceEntity> {
    @Select("""
            SELECT tool_call_id, run_id, message_id, seq, tool_use_id, raw_tool_name, tool_name,
                   args_json, is_concurrent AS concurrent, idempotent, precheck_failed, precheck_error_json, created_at
            FROM agent_tool_call_trace
            WHERE run_id = #{runId}
            ORDER BY seq ASC
            """)
    List<AgentToolCallTraceEntity> findByRunId(@Param("runId") String runId);

    @Select("""
            SELECT c.tool_call_id, c.run_id, c.message_id, c.seq, c.tool_use_id, c.raw_tool_name, c.tool_name,
                   c.args_json, c.is_concurrent AS concurrent, c.idempotent, c.precheck_failed, c.precheck_error_json, c.created_at
            FROM agent_tool_call_trace c
            LEFT JOIN agent_tool_result_trace r ON r.tool_call_id = c.tool_call_id
            WHERE c.run_id = #{runId}
              AND r.tool_call_id IS NULL
            ORDER BY c.seq ASC
            """)
    List<AgentToolCallTraceEntity> findMissingResultsByRunId(@Param("runId") String runId);
}
