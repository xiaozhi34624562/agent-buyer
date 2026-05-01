package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentContextCompactionMapper extends BaseMapper<AgentContextCompactionEntity> {
    @Select("""
            SELECT compaction_id, run_id, turn_no, attempt_id, strategy, before_tokens, after_tokens, compacted_message_ids, created_at
            FROM agent_context_compaction
            WHERE run_id = #{runId}
            ORDER BY created_at ASC
            """)
    List<AgentContextCompactionEntity> findByRunId(@Param("runId") String runId);
}
