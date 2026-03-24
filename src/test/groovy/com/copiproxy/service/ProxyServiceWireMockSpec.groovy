package com.copiproxy.service

import com.copiproxy.config.CopiProxyProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import java.nio.charset.StandardCharsets

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.withRequestBody

class ProxyServiceWireMockSpec extends Specification {

    WireMockServer wm
    TokenResolverService tokenResolver
    ProxyService proxy

    def setup() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wm.start()
        tokenResolver = Mock(TokenResolverService)
        def props = new CopiProxyProperties(
                "http://unused",
                "http://unused",
                wm.baseUrl(),
                "http://unused-token",
                "unused-client"
        )
        proxy = new ProxyService(props, tokenResolver)
    }

    def cleanup() {
        wm?.stop()
    }

    def "proxy GET models forwards query string and returns upstream body"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/models"))
                .withQueryParam("limit", equalTo("10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('[{"id":"gpt-4"}]')
                ))

        def req = new MockHttpServletRequest("GET", "/api/models")
        req.setQueryString("limit=10")

        when:
        def resp = proxy.proxy("/models", req, null)

        then:
        resp.statusCode.value() == 200
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        out.toString(StandardCharsets.UTF_8) == '[{"id":"gpt-4"}]'
        0 * tokenResolver.resolveToken(_)

        and:
        wm.verify(1, getRequestedFor(urlPathEqualTo("/models")).withQueryParam("limit", equalTo("10")))
    }

    def "proxy POST chat completions resolves token and forwards Copilot authorization"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[]}')
                ))

        def req = new MockHttpServletRequest("POST", "/api/chat/completions")
        req.addHeader("Authorization", "Bearer raw-key")
        req.addHeader("X-Custom", "v")
        byte[] body = '{"model":"m"}'.getBytes(StandardCharsets.UTF_8)

        when:
        def resp = proxy.proxy("/chat/completions", req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer raw-key") >> "copilot-runtime-jwt"
        resp.statusCode.value() == 200
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        out.toString(StandardCharsets.UTF_8) == '{"choices":[]}'

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer copilot-runtime-jwt"))
                .withRequestBody(equalTo(new String(body, StandardCharsets.UTF_8))))
    }
}
