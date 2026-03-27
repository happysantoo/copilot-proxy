package com.copiproxy.service;

import com.copiproxy.model.ApiKeyRecord;
import com.copiproxy.model.CopilotMeta;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

@Service
public class TokenResolverService {

    /**
     * Refresh at 75% of the token's lifetime. For a 30-min token this means
     * refreshing ~22.5 min in, leaving 7.5 min of headroom.
     */
    private static final double REFRESH_FRACTION = 0.75;

    private static final long SKEW_MS = 5 * 60_000;
    private static final long MIN_REFRESH_DELAY_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(TokenResolverService.class);

    private final GithubCopilotService githubCopilotService;
    private final ApiKeyService apiKeyService;
    private final Map<String, CopilotMeta> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CopilotMeta>> inflight = new ConcurrentHashMap<>();

    private final Map<String, String> keysByCacheKey = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("token-refresh").factory());

    public TokenResolverService(GithubCopilotService githubCopilotService, ApiKeyService apiKeyService) {
        this.githubCopilotService = githubCopilotService;
        this.apiKeyService = apiKeyService;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public String resolveToken(String authorizationHeader) {
        String resolvedKey = resolveUserKey(authorizationHeader);
        String cacheKey = Integer.toHexString(resolvedKey.hashCode());
        CopilotMeta cached = cache.get(cacheKey);
        if (isFresh(cached)) {
            return cached.token();
        }

        return fetchAndCache(resolvedKey, cacheKey);
    }

    /**
     * Evict the cached token for the given auth header and fetch a fresh one.
     * Called by ProxyService on upstream 401 to transparently recover from expired tokens.
     */
    public String refreshToken(String authorizationHeader) {
        String resolvedKey = resolveUserKey(authorizationHeader);
        String cacheKey = Integer.toHexString(resolvedKey.hashCode());
        cache.remove(cacheKey);
        inflight.remove(cacheKey);
        log.info("Evicted expired Copilot token, fetching fresh one");
        return fetchAndCache(resolvedKey, cacheKey);
    }

    private String resolveUserKey(String authorizationHeader) {
        String userKey = parseBearer(authorizationHeader);
        if (isGithubToken(userKey)) {
            log.debug("Using request GitHub token [cacheKey={}]", Integer.toHexString(userKey.hashCode()));
            return userKey;
        }
        if (userKey != null && !userKey.isBlank() && !"_".equals(userKey)) {
            log.debug("Ignoring non-GitHub token (prefix={}), falling back to default key",
                    userKey.length() > 6 ? userKey.substring(0, 6) + "..." : "***");
        }
        ApiKeyRecord defaultKey = apiKeyService.getDefaultKey();
        if (defaultKey == null) {
            throw new IllegalStateException("No default API key configured");
        }
        log.debug("Using default API key [cacheKey={}]", Integer.toHexString(defaultKey.key().hashCode()));
        return defaultKey.key();
    }

    private static boolean isGithubToken(String key) {
        if (key == null || key.isBlank()) return false;
        return key.startsWith("ghu_") || key.startsWith("gho_")
                || key.startsWith("ghp_") || key.startsWith("github_pat_");
    }

    private String fetchAndCache(String resolvedKey, String cacheKey) {
        CompletableFuture<CopilotMeta> future = inflight.computeIfAbsent(cacheKey, key ->
                CompletableFuture.supplyAsync(() -> githubCopilotService.fetchCopilotMeta(resolvedKey))
                        .thenApply(meta -> {
                            cache.put(cacheKey, meta);
                            keysByCacheKey.put(cacheKey, resolvedKey);
                            scheduleRefresh(cacheKey, meta);
                            return meta;
                        })
                        .whenComplete((meta, throwable) -> inflight.remove(cacheKey))
        );
        return future.join().token();
    }

    private void scheduleRefresh(String cacheKey, CopilotMeta meta) {
        long now = System.currentTimeMillis();
        long ttlMs = meta.expiresAt() - now;
        long delayMs = Math.max((long) (ttlMs * REFRESH_FRACTION), MIN_REFRESH_DELAY_MS);

        log.info("Token TTL is {}s, scheduling proactive refresh in {}s",
                ttlMs / 1000, delayMs / 1000);

        scheduler.schedule(() -> doProactiveRefresh(cacheKey), delayMs, TimeUnit.MILLISECONDS);
    }

    void doProactiveRefresh(String cacheKey) {
        String resolvedKey = keysByCacheKey.get(cacheKey);
        if (resolvedKey == null) return;

        try {
            log.info("Proactive token refresh starting for cache key {}", cacheKey);
            CopilotMeta meta = githubCopilotService.fetchCopilotMeta(resolvedKey);
            cache.put(cacheKey, meta);
            scheduleRefresh(cacheKey, meta);
            log.info("Proactive token refresh succeeded, next refresh in {}s",
                    Math.max((long) ((meta.expiresAt() - System.currentTimeMillis()) * REFRESH_FRACTION), MIN_REFRESH_DELAY_MS) / 1000);
        } catch (Exception e) {
            log.warn("Proactive token refresh failed, will retry in 30s: {}", e.getMessage());
            scheduler.schedule(() -> doProactiveRefresh(cacheKey), MIN_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private boolean isFresh(CopilotMeta meta) {
        return meta != null && (meta.expiresAt() - SKEW_MS > System.currentTimeMillis());
    }

    private String parseBearer(String header) {
        if (header == null) return null;
        return header.replaceFirst("^(Bearer|token)\\s+", "");
    }
}
