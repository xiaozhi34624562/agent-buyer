package com.ai.agent.trajectory.store;

import com.ai.agent.persistence.entity.AgentRunContextEntity;
import com.ai.agent.persistence.mapper.AgentRunContextMapper;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.RunContextStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class MybatisRunContextStore implements RunContextStore {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final AgentRunContextMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisRunContextStore(AgentRunContextMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(RunContext context) {
        AgentRunContextEntity entity = new AgentRunContextEntity();
        entity.setRunId(context.runId());
        entity.setEffectiveAllowedTools(writeJson(context.effectiveAllowedTools()));
        entity.setModel(context.model());
        entity.setPrimaryProvider(context.primaryProvider());
        entity.setFallbackProvider(context.fallbackProvider());
        entity.setProviderOptions(context.providerOptions());
        entity.setMaxTurns(context.maxTurns());
        mapper.insert(entity);
    }

    @Override
    public RunContext load(String runId) {
        AgentRunContextEntity entity = mapper.selectById(runId);
        if (entity == null) {
            throw new RunContextNotFoundException("run context not found: " + runId);
        }
        return new RunContext(
                entity.getRunId(),
                readTools(entity.getEffectiveAllowedTools()),
                entity.getModel(),
                Objects.requireNonNull(entity.getPrimaryProvider(), "run context primaryProvider missing"),
                Objects.requireNonNull(entity.getFallbackProvider(), "run context fallbackProvider missing"),
                Objects.requireNonNull(entity.getProviderOptions(), "run context providerOptions missing"),
                Objects.requireNonNull(entity.getMaxTurns(), "run context maxTurns missing"),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize run context tools", e);
        }
    }

    private List<String> readTools(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read run context tools", e);
        }
    }

    public static final class RunContextNotFoundException extends RuntimeException {
        public RunContextNotFoundException(String message) {
            super(message);
        }
    }
}
