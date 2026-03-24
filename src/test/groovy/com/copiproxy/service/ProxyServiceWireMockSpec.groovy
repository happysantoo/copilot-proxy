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
                "claude-opus-4",
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

    def "proxy GET models injects auth and forwards query string"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/models"))
                .withQueryParam("limit", equalTo("10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('[{"id":"claude-opus-4"}]')
                ))

        def req = new MockHttpServletRequest("GET", "/api/models")
        req.setQueryString("limit=10")
        req.addHeader("Authorization", "Bearer my-key")

        when:
        def resp = proxy.proxy("/models", req, null)

        then:
        1 * tokenResolver.resolveToken("Bearer my-key") >> "copilot-jwt"
        resp.statusCode.value() == 200
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        out.toString(StandardCharsets.UTF_8) == '[{"id":"claude-opus-4"}]'

        and:
        wm.verify(1, getRequestedFor(urlPathEqualTo("/models"))
                .withQueryParam("limit", equalTo("10"))
                .withHeader("Authorization", equalTo("Bearer copilot-jwt"))
                .withHeader("openai-organization", equalTo("github-copilot"))
                .withHeader("x-github-api-version", equalTo("2025-01-21"))
                .withHeader("editor-version", equalTo("vscode/1.98.0"))
                .withHeader("x-request-id", matching("^[0-9a-f\\-]{36}\$"))
        )
    }

    def "proxy POST chat completions resolves token and sends all Copilot headers"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/api/chat/completions")
        req.addHeader("Authorization", "Bearer raw-key")
        byte[] body = '{"model":"claude-sonnet-4.6","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxy("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer raw-key") >> "copilot-runtime-jwt"
        resp.statusCode.value() == 200

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer copilot-runtime-jwt"))
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
                .withRequestBody(containing("claude-sonnet-4.6"))
        )
    }

    def "proxy POST chat completions injects default model when model is missing"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/api/chat/completions")
        req.addHeader("Authorization", "Bearer k")
        byte[] body = '{"messages":[{"role":"user","content":"hi"}]}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxy("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        resp.statusCode.value() == 200

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing('"model":"claude-opus-4"'))
        )
    }

    def "proxy POST chat completions injects default model when model is blank"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/api/chat/completions")
        req.addHeader("Authorization", "Bearer k")
        byte[] body = '{"model":"","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxy("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing('"model":"claude-opus-4"'))
        )
    }

    def "proxy POST chat completions preserves explicit model"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/api/chat/completions")
        req.addHeader("Authorization", "Bearer k")
        byte[] body = '{"model":"claude-sonnet-4.6","messages":[]}'.getBytes(StandardCharsets.UTF_8)

        when:
        proxy.proxy("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing("claude-sonnet-4.6"))
        )
    }
}
