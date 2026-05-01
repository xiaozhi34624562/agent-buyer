package com.ai.agent.subagent.runtime;

import com.ai.agent.subagent.model.ChildReleaseReason;
import com.ai.agent.subagent.model.ChildRunRef;
import com.ai.agent.subagent.model.ParentLinkStatus;
import com.ai.agent.subagent.model.ReserveChildCommand;
import com.ai.agent.subagent.model.ReserveChildResult;
import java.util.List;

public interface ChildRunRegistry {
    ReserveChildResult reserve(ReserveChildCommand command);

    boolean release(
            String parentRunId,
            String childRunId,
            ChildReleaseReason reason,
            ParentLinkStatus parentLinkStatus
    );

    List<ChildRunRef> findActiveChildren(String parentRunId);
}
