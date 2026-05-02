package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
 * Agent工具结果追踪Mapper接口
 * <p>
 * 提供Agent工具结果追踪实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了结果记录的插入或更新（upsert）功能。
 * </p>
 */
public interface AgentToolResultTraceMapper extends BaseMapper<AgentToolResultTraceEntity> {

    /**
     * 插入或更新工具结果追踪记录
     * <p>
     * 当记录不存在时插入新记录，存在时更新状态、结果、错误和取消原因等字段。
     * </p>
     *
     * @param entity 工具结果追踪实体
     * @return 受影响的行数
     */
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
