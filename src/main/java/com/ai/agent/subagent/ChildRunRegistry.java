package com.ai.agent.subagent;

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
