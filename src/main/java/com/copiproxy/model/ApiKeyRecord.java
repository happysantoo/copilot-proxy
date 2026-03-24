package com.copiproxy.model;

public record ApiKeyRecord(
        String id,
        String name,
        String key,
        boolean isDefault,
        long createdAt,
        Long lastUsed,
        int usageCount,
        CopilotMeta meta
) {
}
