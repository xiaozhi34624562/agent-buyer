package com.ai.agent.trajectory.store;

import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.ai.agent.persistence.mapper.AgentContextCompactionMapper;
import com.ai.agent.trajectory.model.ContextCompactionRecord;
import com.ai.agent.trajectory.port.ContextCompactionStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 上下文压缩 Mybatis 存储实现。
 * <p>
 * 基于 Mybatis 实现压缩记录的持久化存储。
 * </p>
 */
@Service
public class MybatisContextCompactionStore implements ContextCompactionStore {

    /** 压缩 Mapper */
    private final AgentContextCompactionMapper mapper;

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param mapper       压缩 Mapper
     * @param objectMapper JSON 对象映射器
     */
    public MybatisContextCompactionStore(AgentContextCompactionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录压缩信息。
     *
     * @param record 压缩记录
     * @return 压缩标识
     */
    @Override
    public String record(ContextCompactionRecord record) {
        String compactionId = record.compactionId() == null ? Ids.newId("cmp") : record.compactionId();
        AgentContextCompactionEntity entity = new AgentContextCompactionEntity();
        entity.setCompactionId(compactionId);
        entity.setRunId(record.runId());
        entity.setTurnNo(record.turnNo());
        entity.setAttemptId(record.attemptId());
        entity.setStrategy(record.strategy());
        entity.setBeforeTokens(record.beforeTokens());
        entity.setAfterTokens(record.afterTokens());
        entity.setCompactedMessageIds(writeJson(record.compactedMessageIds()));
        entity.setCreatedAt(record.createdAt());
        mapper.insert(entity);
        return compactionId;
    }

    /**
     * 序列化对象为 JSON。
     *
     * @param value 对象值
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize compacted message ids", e);
        }
    }
}
