package com.copiproxy.service;

import com.copiproxy.model.ApiKeyRecord;
import com.copiproxy.model.CopilotMeta;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenResolverService {
    private static final long SKEW_MS = 60_000;

    private final GithubCopilotService githubCopilotService;
    private final ApiKeyService apiKeyService;
    private final Map<String, CopilotMeta> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CopilotMeta>> inflight = new ConcurrentHashMap<>();

    public TokenResolverService(GithubCopilotService githubCopilotService, ApiKeyService apiKeyService) {
        this.githubCopilotService = githubCopilotService;
        this.apiKeyService = apiKeyService;
    }

    public String resolveToken(String authorizationHeader) {
        String userKey = parseBearer(authorizationHeader);
        if (userKey == null || userKey.isBlank() || "_".equals(userKey)) {
            ApiKeyRecord defaultKey = apiKeyService.getDefaultKey();
            if (defaultKey == null) {
                throw new IllegalStateException("No default API key configured");
            }
            userKey = defaultKey.key();
        }

        String resolvedKey = userKey;
        String cacheKey = Integer.toHexString(resolvedKey.hashCode());
        CopilotMeta cached = cache.get(cacheKey);
        if (isFresh(cached)) {
            return cached.token();
        }

        CompletableFuture<CopilotMeta> existing = inflight.get(cacheKey);
        if (existing != null) {
            return existing.join().token();
        }

        CompletableFuture<CopilotMeta> created = CompletableFuture
                .supplyAsync(() -> githubCopilotService.fetchCopilotMeta(resolvedKey))
                .whenComplete((meta, throwable) -> inflight.remove(cacheKey));
        inflight.put(cacheKey, created);
        CopilotMeta meta = created.join();
        cache.put(cacheKey, meta);
        return meta.token();
    }

    private boolean isFresh(CopilotMeta meta) {
        return meta != null && (meta.expiresAt() - SKEW_MS > System.currentTimeMillis());
    }

    private String parseBearer(String header) {
        if (header == null) return null;
        return header.replaceFirst("^(Bearer|token)\\s+", "");
    }
}
