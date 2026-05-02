package com.ai.agent.business.user;

/**
 * 用户档案存储接口
 * <p>
 * 定义用户档案数据访问的核心接口，提供按用户ID查询用户档案的功能。
 * </p>
 */
public interface UserProfileStore {

    /**
     * 根据用户ID查询用户档案信息
     *
     * @param userId 用户ID
     * @return 用户档案信息
     */
    UserProfile findByUserId(String userId);
}
