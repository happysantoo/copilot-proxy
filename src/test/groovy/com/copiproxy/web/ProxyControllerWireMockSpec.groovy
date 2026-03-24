package com.copiproxy.web

import com.copiproxy.config.CopiProxyProperties
import com.copiproxy.service.MessageTranslationService
import com.copiproxy.service.ProxyService
import com.copiproxy.service.TokenResolverService
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import java.nio.charset.StandardCharsets

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.containing
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

class ProxyControllerWireMockSpec extends Specification {

    WireMockServer wm
    TokenResolverService tokenResolver
    ProxyController controller
    ObjectMapper mapper = new ObjectMapper()

    def setup() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wm.start()
        tokenResolver = Mock(TokenResolverService)
        def props = new CopiProxyProperties(
                "http://unused", "http://unused",
                wm.baseUrl(), "http://unused", "unused",
                "claude-opus-4-6",
                "vscode/1.98.0", "copilot-chat/0.23.2",
                "GitHubCopilotChat/0.23.2", "vscode-chat",
                "github-copilot", "conversation-panel", "2025-01-21"
        )
        def proxyService = new ProxyService(props, tokenResolver)
        def translationService = new MessageTranslationService(props)
        controller = new ProxyController(proxyService, translationService)
    }

    def cleanup() {
        wm?.stop()
    }

    def "messages non-streaming returns Anthropic format response"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "test-key")
        req.addHeader("content-type", "application/json")
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [[role: "user", content: "Say hello"]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def result = mapper.readTree(out.toByteArray())

        then:
        1 * tokenResolver.resolveToken("Bearer test-key") >> "jwt"
        resp.statusCode.value() == 200
        result.path("type").asText() == "message"
        result.path("role").asText() == "assistant"
        result.path("model").asText() == "claude-opus-4-6"
        result.path("content").get(0).path("type").asText() == "text"
        result.path("content").get(0).path("text").asText() == "Hello!"
        result.path("stop_reason").asText() == "end_turn"
        result.path("usage").path("input_tokens").asInt() == 10
        result.path("usage").path("output_tokens").asInt() == 5

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing('"model":"claude-opus-4-6"'))
                .withRequestBody(containing('"max_tokens":100'))
        )
    }

    def "messages streaming returns Anthropic SSE events"() {
        given:
        def streamBody = [
                'data: {"id":"c","choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}',
                '',
                'data: {"id":"c","choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}',
                '',
                'data: {"id":"c","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}',
                '',
                'data: [DONE]',
                ''
        ].join('\n')

        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(streamBody)
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        req.addHeader("content-type", "application/json")
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 50,
                stream    : true,
                messages  : [[role: "user", content: "Hi"]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def events = out.toString(StandardCharsets.UTF_8)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        resp.statusCode.value() == 200
        events.contains("event: message_start")
        events.contains("event: content_block_start")
        events.contains("event: content_block_delta")
        events.contains('"text":"Hi"')
        events.contains("event: content_block_stop")
        events.contains("event: message_delta")
        events.contains('"stop_reason":"end_turn"')
        events.contains("event: message_stop")
    }

    def "messages with tool use translates correctly"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"id":"c","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"/tmp/x\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":10}}')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 200,
                messages  : [[role: "user", content: "Read /tmp/x"]],
                tools     : [[name: "read_file", description: "Read a file", input_schema: [type: "object", properties: [path: [type: "string"]]]]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def result = mapper.readTree(out.toByteArray())

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        result.path("stop_reason").asText() == "tool_use"
        result.path("content").get(0).path("type").asText() == "tool_use"
        result.path("content").get(0).path("id").asText() == "call_1"
        result.path("content").get(0).path("name").asText() == "read_file"
        result.path("content").get(0).path("input").path("path").asText() == "/tmp/x"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing('"type":"function"'))
                .withRequestBody(containing('"name":"read_file"'))
        )
    }

    def "messages returns Anthropic error on upstream 401 after retry"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('{"error":"unauthorized"}')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "bad-key")
        def body = mapper.writeValueAsBytes([
                model: "claude-opus-4-6", max_tokens: 10,
                messages: [[role: "user", content: "hi"]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def result = mapper.readTree(out.toByteArray())

        then: "first attempt + token refresh + retry"
        1 * tokenResolver.resolveToken("Bearer bad-key") >> "jwt"
        1 * tokenResolver.refreshToken("Bearer bad-key") >> "jwt-refreshed"
        1 * tokenResolver.resolveToken("Bearer bad-key") >> "jwt-refreshed"
        resp.statusCode.value() == 401
        result.path("type").asText() == "error"
        result.path("error").path("type").asText() == "authentication_error"

        and: "proxy hit Copilot twice (original + retry)"
        wm.verify(2, postRequestedFor(urlPathEqualTo("/chat/completions")))
    }

    def "messages returns Anthropic error on upstream 429"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody('rate limited')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        def body = mapper.writeValueAsBytes([
                model: "claude-opus-4-6", max_tokens: 10,
                messages: [[role: "user", content: "hi"]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def result = mapper.readTree(out.toByteArray())

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        resp.statusCode.value() == 429
        result.path("type").asText() == "error"
        result.path("error").path("type").asText() == "rate_limit_error"
    }

    def "messages injects default model when missing"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        def body = mapper.writeValueAsBytes([
                max_tokens: 10,
                messages  : [[role: "user", content: "hi"]]
        ])

        when:
        controller.messages(req, body)

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"

        and:
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing('"model":"claude-opus-4-6"'))
        )
    }

    def "messages streaming with error returns error in non-streaming format"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody('internal error')
                ))

        def req = new MockHttpServletRequest("POST", "/v1/messages")
        req.addHeader("x-api-key", "k")
        def body = mapper.writeValueAsBytes([
                model: "claude-opus-4-6", max_tokens: 10,
                stream: true,
                messages: [[role: "user", content: "hi"]]
        ])

        when:
        def resp = controller.messages(req, body)
        def out = new ByteArrayOutputStream()
        resp.body.writeTo(out)
        def result = mapper.readTree(out.toByteArray())

        then:
        1 * tokenResolver.resolveToken("Bearer k") >> "jwt"
        resp.statusCode.value() == 500
        result.path("type").asText() == "error"
        result.path("error").path("type").asText() == "api_error"
    }
}
