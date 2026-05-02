package com.ai.agent.business.user;

/**
 * 用户档案领域模型记录
 * <p>
 * 表示用户的基本信息，包括用户ID、显示名称、联系方式和角色信息。
 * </p>
 *
 * @param userId      用户ID
 * @param displayName 显示名称
 * @param phone       电话号码
 * @param email       电子邮箱
 * @param address     地址
 * @param roleName    角色名称
 */
public record UserProfile(
        String userId,
        String displayName,
        String phone,
        String email,
        String address,
        String roleName
) {
}
