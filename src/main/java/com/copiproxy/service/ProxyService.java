package com.copiproxy.service;

import com.copiproxy.config.CopiProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
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
import java.time.Duration;
import java.util.Enumeration;

@Service
public class ProxyService {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final CopiProxyProperties properties;
    private final TokenResolverService tokenResolverService;

    public ProxyService(CopiProxyProperties properties, TokenResolverService tokenResolverService) {
        this.properties = properties;
        this.tokenResolverService = tokenResolverService;
    }

    public ResponseEntity<StreamingResponseBody> proxy(String upstreamPath, HttpServletRequest request, byte[] body) {
        try {
            String query = request.getQueryString();
            String targetUrl = properties.copilotApiUrl() + upstreamPath + (query == null ? "" : "?" + query);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofMinutes(5))
                    .method(request.getMethod(), bodyPublisher(request.getMethod(), body));

            copyHeaders(request, builder, upstreamPath, body);
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

    private HttpRequest.BodyPublisher bodyPublisher(String method, byte[] body) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body);
    }

    private void copyHeaders(HttpServletRequest req, HttpRequest.Builder builder, String path, byte[] body) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)) {
                continue;
            }
            builder.header(name, req.getHeader(name));
        }

        builder.header("editor-version", "CopiProxy/0.1.0");
        builder.header("copilot-integration-id", "vscode-chat");
        builder.header("copilot-vision-request", "true");
        builder.header("user-agent", "CopiProxy");

        if (path.startsWith("/chat/completions")) {
            String copilotBearer = tokenResolverService.resolveToken(req.getHeader("authorization"));
            builder.header("authorization", "Bearer " + copilotBearer);
        }
    }
}
