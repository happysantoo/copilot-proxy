package com.copiproxy.web

import com.copiproxy.service.ProxyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import spock.lang.Specification

class ProxyControllerSpec extends Specification {

    def proxyService = Mock(ProxyService)
    def request = Mock(HttpServletRequest)
    def controller = new ProxyController(proxyService)

    def "chatCompletions delegates to proxy service"() {
        given:
        byte[] body = '{"m":"x"}'.bytes
        ResponseEntity<StreamingResponseBody> expected = ResponseEntity.status(HttpStatus.OK).body({ out -> } as StreamingResponseBody)

        when:
        def result = controller.chatCompletions(request, body)

        then:
        1 * proxyService.proxy("/chat/completions", request, body) >> expected
        result == expected
    }

    def "models delegates to proxy service"() {
        given:
        ResponseEntity<StreamingResponseBody> expected = ResponseEntity.status(HttpStatus.OK).body({ out -> } as StreamingResponseBody)

        when:
        def result = controller.models(request)

        then:
        1 * proxyService.proxy("/models", request, null) >> expected
        result == expected
    }
}
