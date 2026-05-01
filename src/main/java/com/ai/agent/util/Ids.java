package com.ai.agent.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId(String prefix) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + Long.toString(Instant.now().toEpochMilli(), 36) + "_" + HexFormat.of().formatHex(bytes);
    }
}
