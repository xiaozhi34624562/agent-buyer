package com.ai.agent.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis Lua脚本加载工具类。
 *
 * <p>用于加载classpath下的Lua脚本文件并创建RedisScript对象，
 * 便于在Redis中执行Lua脚本实现原子性操作。
 *
 * @author ai-agent
 */
public final class RedisLuaScripts {
    /**
     * 私有构造函数，防止实例化。
     */
    private RedisLuaScripts() {
    }

    /**
     * 加载classpath下的Lua脚本。
     *
     * <p>从指定资源路径加载Lua脚本文件，创建并配置DefaultRedisScript对象。
     *
     * @param resourcePath Lua脚本的classpath资源路径
     * @param resultType 脚本执行结果的返回类型
     * @param <T> 返回类型的泛型参数
     * @return 配置好的DefaultRedisScript实例
     */
    public static <T> DefaultRedisScript<T> load(String resourcePath, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resourcePath));
        script.setResultType(resultType);
        return script;
    }
}