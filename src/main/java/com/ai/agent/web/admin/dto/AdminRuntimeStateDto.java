package com.ai.agent.web.admin.dto;

import java.util.Map;

/**
 * 管理后台运行状态数据传输对象。
 *
 * <p>表示单个运行实例的实时状态信息，用于监控和诊断。
 *
 * @param runId    运行实例 ID
 * @param activeRun 是否为活跃运行
 * @param entries   状态数据条目
 * @author AI Agent
 */
public record AdminRuntimeStateDto(
        String runId,
        boolean activeRun,
        Map<String, Object> entries
) {
}