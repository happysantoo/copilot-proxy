package com.copiproxy.config

import spock.lang.Specification

class CopiProxyPropertiesSpec extends Specification {

    def "properties record maps constructor values"() {
        when:
        def props = new CopiProxyProperties(
                "https://github.com/login/device/code",
                "https://github.com/login/oauth/access_token",
                "https://api.githubcopilot.com",
                "https://api.github.com/copilot_internal/v2/token",
                "client-id",
                "claude-opus-4",
                "vscode/1.98.0",
                "copilot-chat/0.23.2",
                "GitHubCopilotChat/0.23.2",
                "vscode-chat",
                "github-copilot",
                "conversation-panel",
                "2025-01-21"
        )

        then:
        props.deviceCodeUrl() == "https://github.com/login/device/code"
        props.oauthUrl() == "https://github.com/login/oauth/access_token"
        props.copilotApiUrl() == "https://api.githubcopilot.com"
        props.copilotTokenUrl() == "https://api.github.com/copilot_internal/v2/token"
        props.clientId() == "client-id"
        props.defaultModel() == "claude-opus-4"
        props.editorVersion() == "vscode/1.98.0"
        props.openaiOrganization() == "github-copilot"
    }
}
