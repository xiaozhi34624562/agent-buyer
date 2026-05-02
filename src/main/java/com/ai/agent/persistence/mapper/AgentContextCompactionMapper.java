package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent上下文压缩Mapper接口
 * <p>
 * 提供Agent上下文压缩实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了按运行ID查询压缩记录的功能。
 * </p>
 */
public interface AgentContextCompactionMapper extends BaseMapper<AgentContextCompactionEntity> {

    /**
     * 根据运行ID查询所有上下文压缩记录，按创建时间升序排列
     *
     * @param runId Agent运行ID
     * @return 上下文压缩实体列表
     */
    @Select("""
            SELECT compaction_id, run_id, turn_no, attempt_id, strategy, before_tokens, after_tokens, compacted_message_ids, created_at
            FROM agent_context_compaction
            WHERE run_id = #{runId}
            ORDER BY created_at ASC
            """)
    List<AgentContextCompactionEntity> findByRunId(@Param("runId") String runId);
}
