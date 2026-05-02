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

/**
 * 运行上下文 Mybatis 存储实现。
 * <p>
 * 基于 Mybatis 实现运行上下文的持久化存储。
 * </p>
 */
@Service
public class MybatisRunContextStore implements RunContextStore {

    /** 字符串列表类型引用 */
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /** 运行上下文 Mapper */
    private final AgentRunContextMapper mapper;

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param mapper       运行上下文 Mapper
     * @param objectMapper JSON 对象映射器
     */
    public MybatisRunContextStore(AgentRunContextMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建运行上下文。
     *
     * @param context 运行上下文
     */
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

    /**
     * 加载运行上下文。
     *
     * @param runId 运行标识
     * @return 运行上下文
     * @throws RunContextNotFoundException 运行上下文未找到
     */
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

    /**
     * 序列化列表为 JSON。
     *
     * @param value 列表值
     * @return JSON 字符串
     */
    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize run context tools", e);
        }
    }

    /**
     * 反序列化 JSON 为工具列表。
     *
     * @param json JSON 字符串
     * @return 工具列表
     */
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

    /**
     * 运行上下文未找到异常。
     */
    public static final class RunContextNotFoundException extends RuntimeException {

        /**
         * 构造函数。
         *
         * @param message 异常消息
         */
        public RunContextNotFoundException(String message) {
            super(message);
        }
    }
}
