package com.ai.agent.api;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public final class AgentShutdownManager {
    private static final long GRACE_SECONDS = 30;

    private final RunAdmissionController admissionController;
    private final ExecutorService agentExecutor;
    private final ExecutorService toolExecutor;
    private final ScheduledExecutorService sseScheduler;

    public AgentShutdownManager(
            RunAdmissionController admissionController,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("toolExecutor") ExecutorService toolExecutor,
            @Qualifier("sseScheduler") ScheduledExecutorService sseScheduler
    ) {
        this.admissionController = admissionController;
        this.agentExecutor = agentExecutor;
        this.toolExecutor = toolExecutor;
        this.sseScheduler = sseScheduler;
    }

    @PreDestroy
    public void shutdown() {
        admissionController.stopAccepting();
        gracefulShutdown(agentExecutor);
        gracefulShutdown(toolExecutor);
        sseScheduler.shutdownNow();
    }

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
