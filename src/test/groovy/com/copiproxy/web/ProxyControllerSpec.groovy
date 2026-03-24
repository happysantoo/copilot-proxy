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
}
