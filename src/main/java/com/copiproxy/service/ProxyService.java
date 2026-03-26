package com.copiproxy.service;

import com.copiproxy.config.CopiProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private static final Set<String> STRIPPED_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding",
            "upgrade", "keep-alive", "proxy-connection", "te", "trailer",
            "anthropic-version", "anthropic-beta", "x-api-key"
    );

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
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

    /**
     * Forward to Copilot and return the raw upstream response (status + InputStream).
     * Caller is responsible for reading/translating the body.
     */
    public HttpResponse<InputStream> proxyRaw(String upstreamPath, HttpServletRequest request, byte[] body) {
        try {
            HttpResponse<InputStream> upstream = doPost(upstreamPath, request, body);

            if (upstream.statusCode() == 401) {
                log.info("Got 401 from Copilot, refreshing token and retrying");
                upstream.body().close();
                String authKey = resolveAuthKey(request);
                tokenResolverService.refreshToken(authKey);
                upstream = doPost(upstreamPath, request, body);
            }

            if (upstream.statusCode() == 429) {
                log.warn("Copilot returned 429 Too Many Requests for {}", upstreamPath);
            }
            return upstream;
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to proxy request", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to proxy request: " + e.getMessage(), e);
        }
    }

    private HttpResponse<InputStream> doPost(String upstreamPath, HttpServletRequest request, byte[] body)
            throws IOException, InterruptedException {
        String query = request.getQueryString();
        String targetUrl = properties.copilotApiUrl() + upstreamPath + (query == null ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));

        copyHeaders(request, builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * Pass-through proxy for paths that need no translation (e.g. /models).
     */
    public ResponseEntity<StreamingResponseBody> proxy(String upstreamPath, HttpServletRequest request) {
        try {
            String query = request.getQueryString();
            String targetUrl = properties.copilotApiUrl() + upstreamPath + (query == null ? "" : "?" + query);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET();

            copyHeaders(request, builder);
            HttpResponse<InputStream> upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

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

    private void copyHeaders(HttpServletRequest req, HttpRequest.Builder builder) {
        String authKey = resolveAuthKey(req);

        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (STRIPPED_HEADERS.contains(name.toLowerCase())) continue;
            if ("authorization".equalsIgnoreCase(name)) continue;
            builder.header(name, req.getHeader(name));
        }

        String copilotBearer = tokenResolverService.resolveToken(authKey);
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

    /**
     * Anthropic clients send x-api-key; OpenAI clients send Authorization: Bearer.
     * Normalise to "Bearer <key>" for TokenResolverService.
     */
    private String resolveAuthKey(HttpServletRequest req) {
        String xApiKey = req.getHeader("x-api-key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return "Bearer " + xApiKey;
        }
        String auth = req.getHeader("authorization");
        return auth != null ? auth : "";
    }
}
