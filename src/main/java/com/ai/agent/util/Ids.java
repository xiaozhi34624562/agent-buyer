package com.ai.agent.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * ID生成工具类。
 *
 * <p>用于生成唯一标识符，结合时间戳和随机数保证ID的唯一性。
 * 生成的ID格式为：前缀_时间戳（36进制）_随机数（16进制）。
 *
 * @author ai-agent
 */
public final class Ids {
    /**
     * 安全随机数生成器。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 私有构造函数，防止实例化。
     */
    private Ids() {
    }

    /**
     * 生成带有前缀的唯一ID。
     *
     * <p>ID格式为：{prefix}_{timestamp}_{random}，其中timestamp为36进制的毫秒时间戳，
     * random为16字节的随机数（16进制表示）。
     *
     * @param prefix ID前缀，用于区分不同类型的ID
     * @return 生成的唯一ID字符串
     */
    public static String newId(String prefix) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + Long.toString(Instant.now().toEpochMilli(), 36) + "_" + HexFormat.of().formatHex(bytes);
    }
}