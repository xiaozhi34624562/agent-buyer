package com.ai.agent.business;

public record UserProfile(
        String userId,
        String displayName,
        String phone,
        String email,
        String address,
        String roleName
) {
}
