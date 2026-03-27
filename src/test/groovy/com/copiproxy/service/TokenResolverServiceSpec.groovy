package com.copiproxy.service

import com.copiproxy.model.ApiKeyRecord
import com.copiproxy.model.CopilotMeta
import spock.lang.Specification

class TokenResolverServiceSpec extends Specification {

    def githubCopilotService = Mock(GithubCopilotService)
    def apiKeyService = Mock(ApiKeyService)
    def service = new TokenResolverService(githubCopilotService, apiKeyService)

    def "resolves token from provided GitHub bearer key"() {
        given:
        def meta = new CopilotMeta("copilot-token", System.currentTimeMillis() + 600_000L, null, 1, 1)

        when:
        def token = service.resolveToken("Bearer ghu_user-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_user-key") >> meta
        0 * apiKeyService._
        token == "copilot-token"
    }

    def "uses cached value on subsequent calls for same key"() {
        given:
        def meta = new CopilotMeta("cached-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def first = service.resolveToken("Bearer ghu_cache-key")
        def second = service.resolveToken("Bearer ghu_cache-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_cache-key") >> meta
        first == "cached-token"
        second == "cached-token"
    }

    def "falls back to default key when header is blank"() {
        given:
        def defaultRecord = new ApiKeyRecord("id", "name", "default-key", true, 1L, null, 0, null)
        def meta = new CopilotMeta("default-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def token = service.resolveToken("")

        then:
        1 * apiKeyService.getDefaultKey() >> defaultRecord
        1 * githubCopilotService.fetchCopilotMeta("default-key") >> meta
        token == "default-token"
    }

    def "falls back to default key when dummy underscore is provided"() {
        given:
        def defaultRecord = new ApiKeyRecord("id", "name", "default-key", true, 1L, null, 0, null)
        def meta = new CopilotMeta("dummy-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def token = service.resolveToken("Bearer _")

        then:
        1 * apiKeyService.getDefaultKey() >> defaultRecord
        1 * githubCopilotService.fetchCopilotMeta("default-key") >> meta
        token == "dummy-token"
    }

    def "refreshToken evicts cache and fetches fresh token"() {
        given:
        def stale = new CopilotMeta("stale-token", System.currentTimeMillis() + 600_000L, null, null, null)
        def fresh = new CopilotMeta("fresh-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def first = service.resolveToken("Bearer ghu_refresh-key")
        def refreshed = service.refreshToken("Bearer ghu_refresh-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_refresh-key") >> stale
        1 * githubCopilotService.fetchCopilotMeta("ghu_refresh-key") >> fresh
        first == "stale-token"
        refreshed == "fresh-token"
    }

    def "proactive refresh replaces cached token"() {
        given:
        def original = new CopilotMeta("original-token", System.currentTimeMillis() + 600_000L, null, null, null)
        def refreshed = new CopilotMeta("refreshed-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when: "initial resolve populates cache and registers the key for proactive refresh"
        def first = service.resolveToken("Bearer ghu_proactive-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_proactive-key") >> original
        first == "original-token"

        when: "simulate the scheduled proactive refresh firing"
        def cacheKey = Integer.toHexString("ghu_proactive-key".hashCode())
        service.doProactiveRefresh(cacheKey)
        def second = service.resolveToken("Bearer ghu_proactive-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_proactive-key") >> refreshed
        second == "refreshed-token"
    }

    def "proactive refresh retries on failure"() {
        given:
        def meta = new CopilotMeta("token", System.currentTimeMillis() + 600_000L, null, null, null)

        when: "initial resolve"
        service.resolveToken("Bearer ghu_retry-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("ghu_retry-key") >> meta

        when: "proactive refresh fails"
        def cacheKey = Integer.toHexString("ghu_retry-key".hashCode())
        githubCopilotService.fetchCopilotMeta("ghu_retry-key") >> { throw new IllegalStateException("network error") }
        service.doProactiveRefresh(cacheKey)

        then: "does not throw — logs warning and schedules retry"
        noExceptionThrown()
    }

    def "proactive refresh with unknown cache key is a no-op"() {
        when:
        service.doProactiveRefresh("nonexistent")

        then:
        0 * githubCopilotService._
        noExceptionThrown()
    }

    def "shutdown stops scheduler cleanly"() {
        when:
        service.shutdown()

        then:
        noExceptionThrown()
    }

    def "falls back to default key when non-GitHub token is provided (e.g. Anthropic sk- key)"() {
        given:
        def defaultRecord = new ApiKeyRecord("id", "name", "default-key", true, 1L, null, 0, null)
        def meta = new CopilotMeta("default-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def token = service.resolveToken("Bearer sk-ant-api03-fake-key")

        then:
        1 * apiKeyService.getDefaultKey() >> defaultRecord
        1 * githubCopilotService.fetchCopilotMeta("default-key") >> meta
        token == "default-token"
    }

    def "falls back to default key when x-api-key style bearer is provided"() {
        given:
        def defaultRecord = new ApiKeyRecord("id", "name", "default-key", true, 1L, null, 0, null)
        def meta = new CopilotMeta("default-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def token = service.resolveToken("Bearer some-random-key")

        then:
        1 * apiKeyService.getDefaultKey() >> defaultRecord
        1 * githubCopilotService.fetchCopilotMeta("default-key") >> meta
        token == "default-token"
    }

    def "accepts all GitHub token prefixes"() {
        given:
        def meta = new CopilotMeta("gh-token", System.currentTimeMillis() + 600_000L, null, null, null)

        when:
        def token = service.resolveToken("Bearer ${ghToken}")

        then:
        1 * githubCopilotService.fetchCopilotMeta(ghToken) >> meta
        0 * apiKeyService._
        token == "gh-token"

        where:
        ghToken << ["ghu_abc123", "gho_abc123", "ghp_abc123", "github_pat_abc123"]
    }

    def "throws when default key missing"() {
        when:
        service.resolveToken(null)

        then:
        1 * apiKeyService.getDefaultKey() >> null
        def ex = thrown(IllegalStateException)
        ex.message == "No default API key configured"
    }
}
