package com.copiproxy.service

import com.copiproxy.config.CopiProxyProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import java.nio.charset.StandardCharsets

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.containing
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.matching
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

class ProxyServiceWireMockSpec extends Specification {

    WireMockServer wm
    TokenResolverService tokenResolver
    ProxyService proxy
    ObjectMapper mapper = new ObjectMapper()

    def setup() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wm.start()
        tokenResolver = Mock(TokenResolverService)
        def props = new CopiProxyProperties(
                "http://unused",
                "http://unused",
                wm.baseUrl(),
                "http://unused-token",
                "unused-client",
                "claude-opus-4-6",
                "vscode/1.98.0",
                "copilot-chat/0.23.2",
                "GitHubCopilotChat/0.23.2",
                "vscode-chat",
                "github-copilot",
                "conversation-panel",
                "2025-01-21"
        )
        proxy = new ProxyService(props, tokenResolver)
    }

    def cleanup() {
        wm?.stop()
    }

    // ── proxyRaw ─────────────────────────────────────────────────────

    def "proxyRaw POST forwards body and injects Copilot auth from x-api-key"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "my-anthropic-key")
        req.addHeader("anthropic-version", "2023-06-01")
        req.addHeader("content-type", "application/json")
        byte[] body = '{"model":"claude-opus-4-6","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxyRaw("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer my-anthropic-key") >> "copilot-jwt"
        resp.statusCode() == 200

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer copilot-jwt"))
                .withHeader("editor-version", equalTo("vscode/1.98.0"))
                .withHeader("openai-organization", equalTo("github-copilot"))
                .withHeader("x-github-api-version", equalTo("2025-01-21"))
                .withHeader("x-request-id", matching("^[0-9a-f\\-]{36}\$"))
                .withHeader("vscode-machineid", matching("^[0-9a-f]{64}\$"))
                .withRequestBody(containing("claude-opus-4-6"))
        )
    }

    def "proxyRaw strips anthropic-version and anthropic-beta headers"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody('{"choices":[]}')))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        req.addHeader("anthropic-version", "2023-06-01")
        req.addHeader("anthropic-beta", "tools-2024-04-04")
        byte[] body = '{"model":"m","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        proxy.proxyRaw("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withoutHeader("anthropic-version")
                .withoutHeader("anthropic-beta")
                .withoutHeader("x-api-key")
        )
    }

    def "proxyRaw falls back to Authorization header when x-api-key missing"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody('{"choices":[]}')))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("Authorization", "Bearer my-bearer-key")
        byte[] body = '{"model":"m","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        proxy.proxyRaw("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer my-bearer-key") >> "jwt"
    }

    def "proxyRaw sends all Copilot headers"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody('{"choices":[]}')))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        byte[] body = '{"model":"m","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        proxy.proxyRaw("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer jwt"))
                .withHeader("editor-version", equalTo("vscode/1.98.0"))
                .withHeader("editor-plugin-version", equalTo("copilot-chat/0.23.2"))
                .withHeader("user-agent", equalTo("GitHubCopilotChat/0.23.2"))
                .withHeader("copilot-integration-id", equalTo("vscode-chat"))
                .withHeader("openai-organization", equalTo("github-copilot"))
                .withHeader("openai-intent", equalTo("conversation-panel"))
                .withHeader("x-github-api-version", equalTo("2025-01-21"))
                .withHeader("copilot-vision-request", equalTo("true"))
                .withHeader("x-request-id", matching("^[0-9a-f\\-]{36}\$"))
                .withHeader("vscode-machineid", matching("^[0-9a-f]{64}\$"))
        )
    }

    // ── proxy (pass-through for /models) ─────────────────────────────

    def "proxy GET models injects auth and forwards query string"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/models"))
                .withQueryParam("limit", equalTo("10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('[{"id":"claude-opus-4-6"}]')
                ))

        def req = new MockHttpServletRequest("GET", "/v1/models")
        req.setQueryString("limit=10")
        req.addHeader("x-api-key", "my-key")

        when:
        def resp = proxy.proxy("/models", req)

        then:
        1 * tokenResolver.resolveToken("Bearer my-key") >> "copilot-jwt"
        resp.statusCode.value() == 200
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        out.toString(StandardCharsets.UTF_8) == '[{"id":"claude-opus-4-6"}]'

        and:
        wm.verify(1, getRequestedFor(urlPathEqualTo("/models"))
                .withQueryParam("limit", equalTo("10"))
                .withHeader("Authorization", equalTo("Bearer copilot-jwt"))
        )
    }

    // ── 429 logging ──────────────────────────────────────────────────

    def "proxyRaw logs warning on 429 status"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody('{"error":"rate limited"}')))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        byte[] body = '{"model":"m","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxyRaw("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        resp.statusCode() == 429
    }
}
