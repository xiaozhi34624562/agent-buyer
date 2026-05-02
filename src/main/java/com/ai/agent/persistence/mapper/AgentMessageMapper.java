package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent消息Mapper接口
 * <p>
 * 提供Agent消息实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了消息序列号获取和按运行ID查询消息列表的功能。
 * </p>
 */
public interface AgentMessageMapper extends BaseMapper<AgentMessageEntity> {

    /**
     * 获取指定运行ID下消息的下一个序列号
     *
     * @param runId Agent运行ID
     * @return 下一个消息序列号
     */
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM agent_message WHERE run_id = #{runId}")
    Long nextSeq(@Param("runId") String runId);

    /**
     * 根据运行ID查询所有消息列表，按序列号升序排列
     *
     * @param runId Agent运行ID
     * @return 消息实体列表
     */
    @Select("""
            SELECT message_id, run_id, seq, role, content, tool_use_id, tool_calls, extras, created_at
            FROM agent_message
            WHERE run_id = #{runId}
            ORDER BY seq ASC
            """)
    List<AgentMessageEntity> findByRunId(@Param("runId") String runId);
}
