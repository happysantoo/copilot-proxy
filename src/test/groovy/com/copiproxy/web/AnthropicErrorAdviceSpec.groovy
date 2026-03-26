package com.copiproxy.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.resource.NoResourceFoundException
import spock.lang.Specification

class AnthropicErrorAdviceSpec extends Specification {

    def advice = new AnthropicErrorAdvice()
    def mapper = new ObjectMapper()

    def "handleNotFound returns 404 in Anthropic error format"() {
        given:
        def ex = new NoResourceFoundException(HttpMethod.POST, "v1/messages/count_tokens", null)

        when:
        def response = advice.handleNotFound(ex)

        then:
        response.statusCode.value() == 404
        def body = mapper.readTree(response.body)
        body.path("type").asText() == "error"
        body.path("error").path("type").asText() == "not_found_error"
        body.path("error").path("message").asText().contains("not supported by this proxy")
    }

    def "handleIllegalState returns 401 when message contains 401"() {
        given:
        def ex = new IllegalStateException("Copilot token API failed with 401: unauthorized")

        when:
        def response = advice.handleIllegalState(ex)

        then:
        response.statusCode.value() == 401
        def body = mapper.readTree(response.body)
        body.path("error").path("type").asText() == "authentication_error"
    }

    def "handleIllegalState returns 502 for non-auth failures"() {
        given:
        def ex = new IllegalStateException("Connection timed out")

        when:
        def response = advice.handleIllegalState(ex)

        then:
        response.statusCode.value() == 502
        def body = mapper.readTree(response.body)
        body.path("error").path("type").asText() == "api_error"
    }

    def "handleGeneric returns 500 in Anthropic error format"() {
        given:
        def ex = new RuntimeException("something went wrong")

        when:
        def response = advice.handleGeneric(ex)

        then:
        response.statusCode.value() == 500
        def body = mapper.readTree(response.body)
        body.path("type").asText() == "error"
        body.path("error").path("type").asText() == "api_error"
        body.path("error").path("message").asText() == "something went wrong"
    }

    def "handleGeneric handles null message"() {
        given:
        def ex = new RuntimeException((String) null)

        when:
        def response = advice.handleGeneric(ex)

        then:
        response.statusCode.value() == 500
        def body = mapper.readTree(response.body)
        body.path("error").path("message").asText() == "Internal server error"
    }
}
