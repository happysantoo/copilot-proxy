package com.copiproxy.service;

import com.copiproxy.config.CopiProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopiProxyProperties properties;
    private final TokenResolverService tokenResolverService;

    private final String syntheticMachineId;
    private final String syntheticSessionId;

    public ProxyService(CopiProxyProperties properties, TokenResolverService tokenResolverService) {
        this.properties = properties;
        this.tokenResolverService = tokenResolverService;

        byte[] machineBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(machineBytes);
        this.syntheticMachineId = HexFormat.of().formatHex(machineBytes);
        this.syntheticSessionId = UUID.randomUUID() + String.valueOf(System.currentTimeMillis());
    }

    public ResponseEntity<StreamingResponseBody> proxy(String upstreamPath, HttpServletRequest request, byte[] body) {
        try {
            byte[] effectiveBody = body;
            if (upstreamPath.startsWith("/chat/completions") && effectiveBody != null && effectiveBody.length > 0) {
                effectiveBody = ensureDefaultModel(effectiveBody);
            }

            String query = request.getQueryString();
            String targetUrl = properties.copilotApiUrl() + upstreamPath + (query == null ? "" : "?" + query);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofMinutes(5))
                    .method(request.getMethod(), bodyPublisher(request.getMethod(), effectiveBody));

            copyHeaders(request, builder);
            HttpResponse<InputStream> upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (upstream.statusCode() == 429) {
                log.warn("Copilot returned 429 Too Many Requests for {}", upstreamPath);
            }

            HttpHeaders headers = new HttpHeaders();
            String contentType = upstream.headers().firstValue("content-type").orElse("application/json");
            headers.set("content-type", contentType);
            StreamingResponseBody stream = out -> upstream.body().transferTo(out);
            return ResponseEntity.status(upstream.statusCode()).headers(headers).body(stream);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to proxy request", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to proxy request", e);
        }
    }

    byte[] ensureDefaultModel(byte[] body) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(body);
            if (!node.has("model") || node.get("model").isNull() || node.get("model").asText().isBlank()) {
                node.put("model", properties.defaultModel());
                return objectMapper.writeValueAsBytes(node);
            }
        } catch (IOException ignored) {
            // Not valid JSON -- forward as-is and let upstream report the error
        }
        return body;
    }

    private HttpRequest.BodyPublisher bodyPublisher(String method, byte[] body) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body);
    }

    private void copyHeaders(HttpServletRequest req, HttpRequest.Builder builder) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)) {
                continue;
            }
            builder.header(name, req.getHeader(name));
        }

        String copilotBearer = tokenResolverService.resolveToken(req.getHeader("authorization"));
        builder.header("authorization", "Bearer " + copilotBearer);

        builder.header("editor-version", properties.editorVersion());
        builder.header("editor-plugin-version", properties.editorPluginVersion());
        builder.header("user-agent", properties.userAgent());
        builder.header("copilot-integration-id", properties.copilotIntegrationId());
        builder.header("copilot-vision-request", "true");
        builder.header("openai-organization", properties.openaiOrganization());
        builder.header("openai-intent", properties.openaiIntent());
        builder.header("x-github-api-version", properties.githubApiVersion());
        builder.header("x-request-id", UUID.randomUUID().toString());
        builder.header("vscode-machineid", syntheticMachineId);
        builder.header("vscode-sessionid", syntheticSessionId);
    }
}
