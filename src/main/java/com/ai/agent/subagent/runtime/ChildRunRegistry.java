package com.ai.agent.subagent.runtime;

import com.ai.agent.subagent.model.ChildReleaseReason;
import com.ai.agent.subagent.model.ChildRunRef;
import com.ai.agent.subagent.model.ParentLinkStatus;
import com.ai.agent.subagent.model.ReserveChildCommand;
import com.ai.agent.subagent.model.ReserveChildResult;
import java.util.List;

/**
 * 子运行注册表接口。
 * <p>
 * 管理父子代理运行之间的关系，提供子运行的预留、释放和查询能力。
 * </p>
 */
public interface ChildRunRegistry {

    /**
     * 预留子运行资源。
     *
     * @param command 预留命令，包含父运行ID、子运行ID、代理类型等信息
     * @return 预留结果，包含是否接受、拒绝原因等信息
     */
    ReserveChildResult reserve(ReserveChildCommand command);

    /**
     * 释放子运行资源。
     *
     * @param parentRunId      父运行ID
     * @param childRunId       子运行ID
     * @param reason           释放原因
     * @param parentLinkStatus 父子链接状态
     * @return 释放是否成功
     */
    boolean release(
            String parentRunId,
            String childRunId,
            ChildReleaseReason reason,
            ParentLinkStatus parentLinkStatus
    );

    /**
     * 查找指定父运行下的所有活跃子运行。
     *
     * @param parentRunId 父运行ID
     * @return 活跃子运行引用列表
     */
    List<ChildRunRef> findActiveChildren(String parentRunId);
}
