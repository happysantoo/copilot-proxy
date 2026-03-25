package com.copiproxy.config

import spock.lang.Specification

class ProxyEnvironmentConfigSpec extends Specification {

    private Map<String, String> savedProperties = [:]

    private static final List<String> PROXY_PROPS = [
            "http.proxyHost", "http.proxyPort",
            "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts"
    ]

    def setup() {
        PROXY_PROPS.each { savedProperties[it] = System.getProperty(it) }
        PROXY_PROPS.each { System.clearProperty(it) }
    }

    def cleanup() {
        PROXY_PROPS.each { prop ->
            if (savedProperties[prop] != null) {
                System.setProperty(prop, savedProperties[prop])
            } else {
                System.clearProperty(prop)
            }
        }
    }

    def "sets HTTPS proxy from https_proxy (lowercase)"() {
        when:
        ProxyEnvironmentConfig.apply([https_proxy: "http://proxy.corp.com:8080"])

        then:
        System.getProperty("https.proxyHost") == "proxy.corp.com"
        System.getProperty("https.proxyPort") == "8080"
    }

    def "sets HTTPS proxy from HTTPS_PROXY (uppercase)"() {
        when:
        ProxyEnvironmentConfig.apply([HTTPS_PROXY: "http://proxy.corp.com:3128"])

        then:
        System.getProperty("https.proxyHost") == "proxy.corp.com"
        System.getProperty("https.proxyPort") == "3128"
    }

    def "sets HTTPS proxy from mixed case Https_Proxy"() {
        when:
        ProxyEnvironmentConfig.apply([Https_Proxy: "http://mixed.proxy.com:9999"])

        then:
        System.getProperty("https.proxyHost") == "mixed.proxy.com"
        System.getProperty("https.proxyPort") == "9999"
    }

    def "exact lowercase match wins over case-insensitive"() {
        when:
        ProxyEnvironmentConfig.apply([
                https_proxy: "http://lower.proxy.com:1111",
                HTTPS_PROXY: "http://upper.proxy.com:2222"
        ])

        then:
        System.getProperty("https.proxyHost") == "lower.proxy.com"
        System.getProperty("https.proxyPort") == "1111"
    }

    def "sets HTTP proxy from http_proxy"() {
        when:
        ProxyEnvironmentConfig.apply([http_proxy: "http://httpproxy.corp.com:9090"])

        then:
        System.getProperty("http.proxyHost") == "httpproxy.corp.com"
        System.getProperty("http.proxyPort") == "9090"
    }

    def "sets HTTP proxy from mixed case Http_Proxy"() {
        when:
        ProxyEnvironmentConfig.apply([Http_Proxy: "http://mixed.http.proxy.com:7070"])

        then:
        System.getProperty("http.proxyHost") == "mixed.http.proxy.com"
        System.getProperty("http.proxyPort") == "7070"
    }

    def "sets both HTTP and HTTPS proxy when both present"() {
        when:
        ProxyEnvironmentConfig.apply([
                https_proxy: "http://secure.proxy.com:443",
                http_proxy : "http://plain.proxy.com:80"
        ])

        then:
        System.getProperty("https.proxyHost") == "secure.proxy.com"
        System.getProperty("https.proxyPort") == "443"
        System.getProperty("http.proxyHost") == "plain.proxy.com"
        System.getProperty("http.proxyPort") == "80"
    }

    def "defaults HTTPS port to 443 when not specified"() {
        when:
        ProxyEnvironmentConfig.apply([https_proxy: "http://proxy.corp.com"])

        then:
        System.getProperty("https.proxyHost") == "proxy.corp.com"
        System.getProperty("https.proxyPort") == "443"
    }

    def "defaults HTTP port to 80 when not specified"() {
        when:
        ProxyEnvironmentConfig.apply([http_proxy: "http://proxy.corp.com"])

        then:
        System.getProperty("http.proxyHost") == "proxy.corp.com"
        System.getProperty("http.proxyPort") == "80"
    }

    def "converts no_proxy to http.nonProxyHosts with pipe separator"() {
        when:
        ProxyEnvironmentConfig.apply([no_proxy: "localhost, 127.0.0.1, .corp.example.com, internal.dev"])

        then:
        System.getProperty("http.nonProxyHosts") == "localhost|127.0.0.1|*.corp.example.com|internal.dev"
    }

    def "reads NO_PROXY uppercase"() {
        when:
        ProxyEnvironmentConfig.apply([NO_PROXY: "localhost,.internal"])

        then:
        System.getProperty("http.nonProxyHosts") == "localhost|*.internal"
    }

    def "reads No_Proxy mixed case"() {
        when:
        ProxyEnvironmentConfig.apply([No_Proxy: "localhost,.mixed.corp"])

        then:
        System.getProperty("http.nonProxyHosts") == "localhost|*.mixed.corp"
    }

    def "does not override existing system property"() {
        given:
        System.setProperty("https.proxyHost", "manual.proxy.com")
        System.setProperty("https.proxyPort", "1234")

        when:
        ProxyEnvironmentConfig.apply([https_proxy: "http://env.proxy.com:5678"])

        then:
        System.getProperty("https.proxyHost") == "manual.proxy.com"
        System.getProperty("https.proxyPort") == "1234"
    }

    def "handles credentials in URL without crashing"() {
        when:
        ProxyEnvironmentConfig.apply([https_proxy: "http://user:pass@proxy.corp.com:8080"])

        then:
        System.getProperty("https.proxyHost") == "proxy.corp.com"
        System.getProperty("https.proxyPort") == "8080"
    }

    def "no env vars set is a no-op"() {
        when:
        ProxyEnvironmentConfig.apply([:])

        then:
        System.getProperty("https.proxyHost") == null
        System.getProperty("https.proxyPort") == null
        System.getProperty("http.proxyHost") == null
        System.getProperty("http.proxyPort") == null
        System.getProperty("http.nonProxyHosts") == null
    }

    def "malformed URL is skipped gracefully"() {
        when:
        ProxyEnvironmentConfig.apply([https_proxy: "://not-a-valid-url"])

        then:
        noExceptionThrown()
        System.getProperty("https.proxyHost") == null
    }

    def "toJvmPattern converts leading dot to wildcard"() {
        expect:
        ProxyEnvironmentConfig.toJvmPattern(".example.com") == "*.example.com"
        ProxyEnvironmentConfig.toJvmPattern("localhost") == "localhost"
        ProxyEnvironmentConfig.toJvmPattern("127.0.0.1") == "127.0.0.1"
    }
}
