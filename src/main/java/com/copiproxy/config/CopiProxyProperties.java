package com.copiproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copiproxy.github")
public record CopiProxyProperties(
        String deviceCodeUrl,
        String oauthUrl,
        String copilotApiUrl,
        String copilotTokenUrl,
        String clientId
) {
}
