package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface AgentToolResultTraceMapper extends BaseMapper<AgentToolResultTraceEntity> {
    @Insert("""
            INSERT INTO agent_tool_result_trace
                (result_id, tool_call_id, run_id, tool_use_id, status, result_json, error_json, cancel_reason, synthetic)
            VALUES
                (#{resultId}, #{toolCallId}, #{runId}, #{toolUseId}, #{status}, #{resultJson}, #{errorJson}, #{cancelReason}, #{synthetic})
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                result_json = VALUES(result_json),
                error_json = VALUES(error_json),
                cancel_reason = VALUES(cancel_reason),
                synthetic = VALUES(synthetic)
            """)
    int upsertTrace(AgentToolResultTraceEntity entity);
}
