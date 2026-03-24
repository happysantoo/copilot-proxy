package com.copiproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "copiproxy.github")
public record CopiProxyProperties(
        String deviceCodeUrl,
        String oauthUrl,
        String copilotApiUrl,
        String copilotTokenUrl,
        String clientId,
        @DefaultValue("claude-opus-4") String defaultModel,
        @DefaultValue("vscode/1.98.0") String editorVersion,
        @DefaultValue("copilot-chat/0.23.2") String editorPluginVersion,
        @DefaultValue("GitHubCopilotChat/0.23.2") String userAgent,
        @DefaultValue("vscode-chat") String copilotIntegrationId,
        @DefaultValue("github-copilot") String openaiOrganization,
        @DefaultValue("conversation-panel") String openaiIntent,
        @DefaultValue("2025-01-21") String githubApiVersion
) {
}
