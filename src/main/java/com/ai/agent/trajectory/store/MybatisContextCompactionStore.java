package com.ai.agent.trajectory.store;

import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.ai.agent.persistence.mapper.AgentContextCompactionMapper;
import com.ai.agent.trajectory.model.ContextCompactionRecord;
import com.ai.agent.trajectory.port.ContextCompactionStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class MybatisContextCompactionStore implements ContextCompactionStore {
    private final AgentContextCompactionMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisContextCompactionStore(AgentContextCompactionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize compacted message ids", e);
        }
    }
}
