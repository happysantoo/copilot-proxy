package com.copiproxy.model;

public record CopilotMeta(
        String token,
        long expiresAt,
        Long resetTime,
        Integer chatQuota,
        Integer completionsQuota
) {
}
