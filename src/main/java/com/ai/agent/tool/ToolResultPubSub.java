package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public final class ToolResultPubSub implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ToolResultPubSub.class);

    private final AgentProperties properties;
    private final RedisKeys keys;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<CompletableFuture<Void>>> waiters = new ConcurrentHashMap<>();

    public ToolResultPubSub(
            AgentProperties properties,
            RedisKeys keys,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RedisMessageListenerContainer listenerContainer
    ) {
        this.properties = properties;
        this.keys = keys;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        listenerContainer.addMessageListener(this, new PatternTopic(keys.resultChannelPattern()));
    }

    public CompletableFuture<Void> waitFor(String runId, String toolCallId) {
        if (!properties.getRuntime().isToolResultPubsubEnabled()) {
            return new CompletableFuture<>();
        }
        String key = waiterKey(runId, toolCallId);
        CompletableFuture<Void> future = new CompletableFuture<>();
        waiters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(future);
        return future;
    }

    public void cancelWait(String runId, String toolCallId, CompletableFuture<Void> future) {
        if (future == null) {
            return;
        }
        String key = waiterKey(runId, toolCallId);
        CopyOnWriteArrayList<CompletableFuture<Void>> futures = waiters.get(key);
        if (futures == null) {
            return;
        }
        futures.remove(future);
        if (futures.isEmpty()) {
            waiters.remove(key, futures);
        }
    }

    public void publish(String runId, String toolCallId) {
        if (!properties.getRuntime().isToolResultPubsubEnabled()) {
            return;
        }
        completeLocal(runId, toolCallId);
        redisTemplate.convertAndSend(keys.resultChannel(runId), payload(runId, toolCallId));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            ResultNotification notification = objectMapper.readValue(body, ResultNotification.class);
            completeLocal(notification.runId(), notification.toolCallId());
        } catch (Exception e) {
            log.warn("invalid tool result pubsub payload ignored payload={}", body, e);
        }
    }

    private void completeLocal(String runId, String toolCallId) {
        CopyOnWriteArrayList<CompletableFuture<Void>> futures = waiters.remove(waiterKey(runId, toolCallId));
        if (futures == null) {
            return;
        }
        for (CompletableFuture<Void> future : futures) {
            future.complete(null);
        }
    }

    private String payload(String runId, String toolCallId) {
        try {
            return objectMapper.writeValueAsString(new ResultNotification(runId, toolCallId));
        } catch (JsonProcessingException e) {
            return "{\"runId\":\"" + runId + "\",\"toolCallId\":\"" + toolCallId + "\"}";
        }
    }

    private String waiterKey(String runId, String toolCallId) {
        return runId + ":" + toolCallId;
    }

    private record ResultNotification(String runId, String toolCallId) {
    }
}
