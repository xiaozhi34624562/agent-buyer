package com.ai.agent.web.sse;

import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.mapper.AgentEventMapper;
import com.ai.agent.persistence.mapper.AgentToolProgressMapper;
import com.ai.agent.security.SensitivePayloadSanitizer;
import com.ai.agent.tool.model.ToolStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentEventRecorderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordEventRedactsConfirmTokenBeforeAsyncPersistence() {
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        AgentToolProgressMapper progressMapper = mock(AgentToolProgressMapper.class);
        AgentEventRecorder recorder = recorder(eventMapper, progressMapper);
        ArgumentCaptor<AgentEventEntity> captor = ArgumentCaptor.forClass(AgentEventEntity.class);

        recorder.recordEvent("run-1", "tool_result", new ToolResultEvent(
                "run-1",
                "call-1",
                ToolStatus.SUCCEEDED,
                "{\"summary\":\"ok\",\"confirmToken\":\"ct_secret_token\"}",
                null
        ));

        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayloadJson())
                .doesNotContain("ct_secret_token")
                .contains("[REDACTED]");
    }

    @Test
    void recordProgressRedactsConfirmTokenBeforeAsyncPersistence() {
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        AgentToolProgressMapper progressMapper = mock(AgentToolProgressMapper.class);
        AgentEventRecorder recorder = recorder(eventMapper, progressMapper);
        ArgumentCaptor<AgentToolProgressEntity> captor = ArgumentCaptor.forClass(AgentToolProgressEntity.class);

        recorder.recordProgress(new ToolProgressEvent(
                "run-1",
                "tc-1",
                "checking",
                "checking confirmToken ct_secret_progress",
                50
        ));

        verify(progressMapper).insert(captor.capture());
        assertThat(captor.getValue().getMessage())
                .doesNotContain("ct_secret_progress")
                .contains("[REDACTED]");
    }

    private AgentEventRecorder recorder(AgentEventMapper eventMapper, AgentToolProgressMapper progressMapper) {
        return new AgentEventRecorder(
                eventMapper,
                progressMapper,
                new SensitivePayloadSanitizer(objectMapper),
                new DirectExecutorService(),
                new SimpleMeterRegistry()
        );
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
