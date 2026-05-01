package com.ai.agent.trajectory.store;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.mapper.AgentMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisTrajectoryStoreTest {
    @Test
    void appendMessageRetriesWhenConcurrentSeqInsertLosesRace() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(messageMapper.nextSeq("run-1")).thenReturn(7L, 8L);
        doThrow(new DuplicateKeyException("duplicate run seq"))
                .doReturn(1)
                .when(messageMapper)
                .insert(any(AgentMessageEntity.class));
        MybatisTrajectoryStore store = new MybatisTrajectoryStore(
                null,
                messageMapper,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );

        String messageId = store.appendMessage("run-1", LlmMessage.tool("msg-1", "tool-use-1", "{}"));

        assertThat(messageId).isEqualTo("msg-1");
        verify(messageMapper, times(2)).insert(any(AgentMessageEntity.class));
        verify(messageMapper, times(2)).nextSeq("run-1");
    }
}
