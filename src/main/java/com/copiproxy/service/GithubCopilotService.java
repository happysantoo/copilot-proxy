package com.copiproxy.service;

import com.copiproxy.config.CopiProxyProperties;
import com.copiproxy.model.CopilotMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@Service
public class GithubCopilotService {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopiProxyProperties properties;

    public GithubCopilotService(CopiProxyProperties properties) {
        this.properties = properties;
    }

    public CopilotMeta fetchCopilotMeta(String userKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.copilotTokenUrl()))
                .header("Authorization", "token " + userKey)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Copilot token API failed with " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("token").asText();
            long expiresAt = parseExpiresAt(json.path("expires_at").asText());
            JsonNode quotas = json.path("limited_user_quotas");
            Integer chat = quotas.isObject() && !quotas.path("chat").isMissingNode() ? quotas.path("chat").asInt() : null;
            Integer completions = quotas.isObject() && !quotas.path("completions").isMissingNode() ? quotas.path("completions").asInt() : null;
            Long resetTime = json.path("limited_user_reset_date").isNumber()
                    ? json.path("limited_user_reset_date").asLong() * 1000
                    : null;
            return new CopilotMeta(token, expiresAt, resetTime, chat, completions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to fetch copilot token", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch copilot token", e);
        }
    }

    public Map<String, Object> initiateDeviceFlow() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("client_id", properties.clientId());
        payload.put("scope", "read:user");
        return postJson(properties.deviceCodeUrl(), payload);
    }

    public Map<String, Object> verifyDeviceFlow(String deviceCode) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("client_id", properties.clientId());
        payload.put("device_code", deviceCode);
        payload.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        return postJson(properties.oauthUrl(), payload);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("accept", "application/json")
                    .header("content-type", MediaType.APPLICATION_JSON_VALUE)
                    .header("editor-version", "Neovim/0.6.1")
                    .header("editor-plugin-version", "copilot.vim/1.16.0")
                    .header("user-agent", "GithubCopilot/1.155.0")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return objectMapper.readValue(response.body(), objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub OAuth call failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("GitHub OAuth call failed", e);
        }
    }

    private long parseExpiresAt(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return System.currentTimeMillis() + Duration.ofHours(1).toMillis();
        }
    }
}
