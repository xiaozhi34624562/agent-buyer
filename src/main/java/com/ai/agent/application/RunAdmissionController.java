package com.ai.agent.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行准入控制器。
 * 控制系统是否接受新的运行请求，用于实现系统级别的启停控制。
 */
@Component
public final class RunAdmissionController {
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /**
     * 检查系统是否正在接受新运行。
     *
     * @return 如果系统接受新运行则返回true，否则返回false
     */
    public boolean isAccepting() {
        return accepting.get();
    }

    /**
     * 停止接受新的运行请求。
     */
    public void stopAccepting() {
        accepting.set(false);
    }
}
