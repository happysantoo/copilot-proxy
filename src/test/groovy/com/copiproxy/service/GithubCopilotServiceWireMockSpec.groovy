package com.copiproxy.service

import com.copiproxy.config.CopiProxyProperties
import com.copiproxy.model.CopilotMeta
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

class GithubCopilotServiceWireMockSpec extends Specification {

    WireMockServer wm

    def setup() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wm.start()
    }

    def cleanup() {
        wm?.stop()
    }

    private CopiProxyProperties props() {
        String b = wm.baseUrl()
        return new CopiProxyProperties(
                b + "/login/device/code",
                b + "/login/oauth/access_token",
                b + "/copilot-api",
                b + "/copilot_internal/v2/token",
                "test-client-id",
                "claude-opus-4",
                "vscode/1.98.0",
                "copilot-chat/0.23.2",
                "GitHubCopilotChat/0.23.2",
                "vscode-chat",
                "github-copilot",
                "conversation-panel",
                "2025-01-21"
        )
    }

    def "fetchCopilotMeta maps token API JSON to CopilotMeta"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .withHeader("Authorization", equalTo("token my-gh-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"token":"runtime-token","expires_at":"2027-06-15T12:00:00Z","limited_user_quotas":{"chat":11,"completions":22},"limited_user_reset_date":1700000000}')
                ))
        def service = new GithubCopilotService(props())

        when:
        CopilotMeta meta = service.fetchCopilotMeta("my-gh-key")

        then:
        meta.token() == "runtime-token"
        meta.expiresAt() > 0
        meta.chatQuota() == 11
        meta.completionsQuota() == 22
        meta.resetTime() == 1700000000L * 1000
    }

    def "fetchCopilotMeta throws when token API returns error status"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .willReturn(aResponse().withStatus(401).withBody("unauthorized")))
        def service = new GithubCopilotService(props())

        when:
        service.fetchCopilotMeta("key")

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("401")
    }

    def "fetchCopilotMeta uses default expiry when expires_at is not ISO-8601"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"token":"t","expires_at":"not-a-date","limited_user_quotas":null}')
                ))
        def service = new GithubCopilotService(props())
        def before = System.currentTimeMillis()

        when:
        CopilotMeta meta = service.fetchCopilotMeta("k")

        then:
        meta.token() == "t"
        meta.expiresAt() >= before
        meta.chatQuota() == null
    }

    def "initiateDeviceFlow posts JSON and returns response map"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/login/device/code"))
                .withHeader("content-type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"device_code":"dc","user_code":"uc","verification_uri":"https://github.com/login/device","expires_in":900}')
                ))
        def service = new GithubCopilotService(props())

        when:
        def map = service.initiateDeviceFlow()

        then:
        map.get("device_code") == "dc"
        map.get("user_code") == "uc"
        map.get("verification_uri") == "https://github.com/login/device"
        map.get("expires_in") == 900 || map.get("expires_in") == 900d
    }

    def "verifyDeviceFlow posts grant and returns JSON map"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('{"access_token":"oauth-at","token_type":"bearer"}')
                ))
        def service = new GithubCopilotService(props())

        when:
        def map = service.verifyDeviceFlow("device-code-xyz")

        then:
        map.get("access_token") == "oauth-at"
    }

    def "fetchCopilotMeta wraps non-JSON 200 response"() {
        given:
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody('not-json')
                ))
        def service = new GithubCopilotService(props())

        when:
        service.fetchCopilotMeta("key")

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "Failed to fetch copilot token"
    }

    def "verifyDeviceFlow wraps non-JSON OAuth response"() {
        given:
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('<html></html>')
                ))
        def service = new GithubCopilotService(props())

        when:
        service.verifyDeviceFlow("dc")

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "GitHub OAuth call failed"
    }
}
