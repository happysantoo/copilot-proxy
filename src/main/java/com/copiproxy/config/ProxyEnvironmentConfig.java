package com.copiproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps standard proxy environment variables ({@code http_proxy}, {@code https_proxy},
 * {@code no_proxy}) to JVM system properties so that {@link java.net.http.HttpClient}
 * routes traffic through a corporate forward proxy.
 * <p>
 * Must be called <b>before</b> any {@code HttpClient} is constructed — typically
 * from {@code main()} before {@code SpringApplication.run()}.
 * <p>
 * Precedence: explicit {@code -D} flags already set on the JVM are never overwritten.
 */
public final class ProxyEnvironmentConfig {

    private static final Logger log = LoggerFactory.getLogger(ProxyEnvironmentConfig.class);

    private ProxyEnvironmentConfig() {}

    /** Read from real environment. */
    public static void apply() {
        apply(System.getenv());
    }

    /** Testable overload that accepts an env map. */
    static void apply(Map<String, String> env) {
        Function<String, String> lookup = key -> findIgnoreCase(env, key);
        configureProxy(lookup, "https_proxy", "https.proxyHost", "https.proxyPort", 443);
        configureProxy(lookup, "http_proxy", "http.proxyHost", "http.proxyPort", 80);
        configureNoProxy(lookup);
    }

    /**
     * Case-insensitive env var lookup. Scans the map for a key matching
     * the requested name regardless of case (e.g. {@code https_proxy},
     * {@code HTTPS_PROXY}, {@code Https_Proxy} all match).
     */
    private static String findIgnoreCase(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value != null) return value;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void configureProxy(Function<String, String> env,
                                        String varName,
                                        String hostProp, String portProp,
                                        int defaultPort) {
        String url = env.apply(varName);
        if (url == null || url.isBlank()) {
            log.debug("No {} environment variable set (case-insensitive)", varName);
            return;
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("Could not parse host from {} = '{}', skipping", varName, url);
                return;
            }

            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;

            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                log.warn("Proxy URL contains credentials — JVM system properties do not support "
                        + "proxy authentication; use a local proxy helper (e.g. CNTLM) instead");
            }

            setIfAbsent(hostProp, host);
            setIfAbsent(portProp, String.valueOf(port));
            log.info("Configured {} proxy from environment: {}:{}", hostProp.startsWith("https") ? "HTTPS" : "HTTP", host, port);
        } catch (IllegalArgumentException e) {
            log.warn("Malformed proxy URL in {} = '{}': {}", varName, url, e.getMessage());
        }
    }

    private static void configureNoProxy(Function<String, String> env) {
        String noProxy = env.apply("no_proxy");
        if (noProxy == null || noProxy.isBlank()) {
            return;
        }

        String nonProxyHosts = Arrays.stream(noProxy.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ProxyEnvironmentConfig::toJvmPattern)
                .collect(Collectors.joining("|"));

        if (!nonProxyHosts.isEmpty()) {
            setIfAbsent("http.nonProxyHosts", nonProxyHosts);
            log.info("Configured non-proxy hosts from environment: {}", nonProxyHosts);
        }
    }

    /**
     * Convert a {@code no_proxy} entry to JVM {@code http.nonProxyHosts} pattern.
     * Leading dot ({@code .example.com}) becomes wildcard ({@code *.example.com}).
     */
    static String toJvmPattern(String entry) {
        if (entry.startsWith(".")) {
            return "*" + entry;
        }
        return entry;
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) != null) {
            log.debug("System property {} already set, not overriding with env value", property);
            return;
        }
        System.setProperty(property, value);
    }
}
