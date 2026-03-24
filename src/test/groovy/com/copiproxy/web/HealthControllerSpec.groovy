package com.copiproxy.web

import spock.lang.Specification

class HealthControllerSpec extends Specification {

    def "health returns ok status"() {
        given:
        def controller = new HealthController()

        when:
        def result = controller.health()

        then:
        result == [status: "ok"]
    }
}
