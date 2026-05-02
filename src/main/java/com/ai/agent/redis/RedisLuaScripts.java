package com.ai.agent.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisLuaScripts {
    private RedisLuaScripts() {
    }

    public static <T> DefaultRedisScript<T> load(String resourcePath, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resourcePath));
        script.setResultType(resultType);
        return script;
    }
}
