package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent工具调用追踪Mapper接口
 * <p>
 * 提供Agent工具调用追踪实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了按运行ID查询工具调用和查找缺失结果的工具调用功能。
 * </p>
 */
public interface AgentToolCallTraceMapper extends BaseMapper<AgentToolCallTraceEntity> {

    /**
     * 根据运行ID查询所有工具调用追踪记录，按序列号升序排列
     *
     * @param runId Agent运行ID
     * @return 工具调用追踪实体列表
     */
    @Select("""
            SELECT tool_call_id, run_id, message_id, seq, tool_use_id, raw_tool_name, tool_name,
                   args_json, is_concurrent AS concurrent, idempotent, precheck_failed, precheck_error_json, created_at
            FROM agent_tool_call_trace
            WHERE run_id = #{runId}
            ORDER BY seq ASC
            """)
    List<AgentToolCallTraceEntity> findByRunId(@Param("runId") String runId);

    /**
     * 查找缺失结果的工具调用追踪记录
     * <p>
     * 查询指定运行ID下尚未产生结果的工具调用记录。
     * </p>
     *
     * @param runId Agent运行ID
     * @return 缺失结果的工具调用追踪实体列表
     */
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
