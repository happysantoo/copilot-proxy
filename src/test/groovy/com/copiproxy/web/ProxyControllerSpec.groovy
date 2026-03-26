package com.copiproxy.web

import com.copiproxy.service.MessageTranslationService
import com.copiproxy.service.ProxyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import spock.lang.Specification

class ProxyControllerSpec extends Specification {

    def proxyService = Mock(ProxyService)
    def translationService = Mock(MessageTranslationService)
    def request = Mock(HttpServletRequest)
    def controller = new ProxyController(proxyService, translationService)

    def "models delegates to proxy service pass-through"() {
        given:
        ResponseEntity<StreamingResponseBody> expected = ResponseEntity.status(HttpStatus.OK).body({ out -> } as StreamingResponseBody)

        when:
        def result = controller.models(request)

        then:
        1 * proxyService.proxy("/models", request) >> expected
        result == expected
    }

    def "messages returns auth error on IllegalStateException with 401"() {
        given:
        def body = '{"model":"claude-opus-4-6","messages":[{"role":"user","content":"hi"}],"stream":false}'.bytes

        when:
        def result = controller.messages(request, body)

        then:
        1 * translationService.translateRequest(body) >> { throw new IllegalStateException("Copilot token API failed with 401: unauthorized") }
        result.statusCode.value() == 401
        def out = new ByteArrayOutputStream()
        result.body.writeTo(out)
        out.toString().contains("authentication_error")
    }

    def "messages returns 500 on unexpected RuntimeException"() {
        given:
        def body = '{"model":"claude-opus-4-6","messages":[{"role":"user","content":"hi"}],"stream":false}'.bytes

        when:
        def result = controller.messages(request, body)

        then:
        1 * translationService.translateRequest(body) >> { throw new RuntimeException("boom") }
        result.statusCode.value() == 500
        def out = new ByteArrayOutputStream()
        result.body.writeTo(out)
        out.toString().contains("api_error")
    }
}
