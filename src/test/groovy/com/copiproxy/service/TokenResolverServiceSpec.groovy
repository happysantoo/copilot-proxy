package com.copiproxy.service

import com.copiproxy.model.ApiKeyRecord
import com.copiproxy.model.CopilotMeta
import spock.lang.Specification

class TokenResolverServiceSpec extends Specification {

    def githubCopilotService = Mock(GithubCopilotService)
    def apiKeyService = Mock(ApiKeyService)
    def service = new TokenResolverService(githubCopilotService, apiKeyService)

    def "resolves token from provided bearer key"() {
        given:
        def meta = new CopilotMeta("copilot-token", System.currentTimeMillis() + 120_000L, null, 1, 1)

        when:
        def token = service.resolveToken("Bearer user-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("user-key") >> meta
        0 * apiKeyService._
        token == "copilot-token"
    }

    def "uses cached value on subsequent calls for same key"() {
        given:
        def meta = new CopilotMeta("cached-token", System.currentTimeMillis() + 120_000L, null, null, null)

        when:
        def first = service.resolveToken("Bearer cache-key")
        def second = service.resolveToken("Bearer cache-key")

        then:
        1 * githubCopilotService.fetchCopilotMeta("cache-key") >> meta
        first == "cached-token"
        second == "cached-token"
    }

    def "falls back to default key when header is blank"() {
        given:
        def defaultRecord = new ApiKeyRecord("id", "name", "default-key", true, 1L, null, 0, null)
        def meta = new CopilotMeta("default-token", System.currentTimeMillis() + 120_000L, null, null, null)

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
        def meta = new CopilotMeta("dummy-token", System.currentTimeMillis() + 120_000L, null, null, null)

        when:
        def token = service.resolveToken("Bearer _")

        then:
        1 * apiKeyService.getDefaultKey() >> defaultRecord
        1 * githubCopilotService.fetchCopilotMeta("default-key") >> meta
        token == "dummy-token"
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
