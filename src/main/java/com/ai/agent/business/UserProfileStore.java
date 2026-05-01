package com.ai.agent.business;

public interface UserProfileStore {
    UserProfile findByUserId(String userId);
}
