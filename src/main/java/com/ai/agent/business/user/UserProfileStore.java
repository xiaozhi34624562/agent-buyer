package com.ai.agent.business.user;

public interface UserProfileStore {
    UserProfile findByUserId(String userId);
}
