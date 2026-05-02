package com.ai.agent.application;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent关闭管理器
 * <p>
 * 负责在应用关闭时优雅地停止所有相关线程池，确保运行中的任务能够有序完成，
 * 防止 abruptly termination 导致的数据丢失或状态不一致。
 * </p>
 */
@Component
public final class AgentShutdownManager {
    private static final long GRACE_SECONDS = 30;

    private final RunAdmissionController admissionController;
    private final ExecutorService agentExecutor;
    private final ExecutorService toolExecutor;
    private final ExecutorService eventExecutor;
    private final ScheduledExecutorService sseScheduler;

    public AgentShutdownManager(
            RunAdmissionController admissionController,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("toolExecutor") ExecutorService toolExecutor,
            @Qualifier("eventExecutor") ExecutorService eventExecutor,
            @Qualifier("sseScheduler") ScheduledExecutorService sseScheduler
    ) {
        this.admissionController = admissionController;
        this.agentExecutor = agentExecutor;
        this.toolExecutor = toolExecutor;
        this.eventExecutor = eventExecutor;
        this.sseScheduler = sseScheduler;
    }

    /**
     * 执行关闭操作
     * <p>
     * 在应用销毁时调用，停止接收新请求，并优雅关闭所有线程池。
     * SSE调度器直接关闭，不等待任务完成。
     * </p>
     */
    @PreDestroy
    public void shutdown() {
        admissionController.stopAccepting();
        gracefulShutdown(agentExecutor);
        gracefulShutdown(toolExecutor);
        gracefulShutdown(eventExecutor);
        sseScheduler.shutdownNow();
    }

    /**
     * 优雅关闭线程池
     * <p>
     * 先调用shutdown等待任务完成，超时后强制关闭。
     * </p>
     *
     * @param executor 要关闭的线程池
     */
    private void gracefulShutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
