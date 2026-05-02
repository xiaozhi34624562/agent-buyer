package com.ai.agent.trajectory.port;

import com.ai.agent.trajectory.model.ContextCompactionRecord;

/**
 * 上下文压缩存储接口。
 * <p>
 * 提供压缩记录的持久化能力。
 * </p>
 */
public interface ContextCompactionStore {

    /**
     * 记录压缩信息。
     *
     * @param record 压缩记录
     * @return 压缩标识
     */
    String record(ContextCompactionRecord record);
}
