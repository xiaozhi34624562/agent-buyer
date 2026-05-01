package com.ai.agent.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class RunAdmissionController {
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public boolean isAccepting() {
        return accepting.get();
    }

    public void stopAccepting() {
        accepting.set(false);
    }
}
