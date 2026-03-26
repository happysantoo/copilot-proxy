package com.copiproxy.web;

import com.copiproxy.service.MessageTranslationService;
import com.copiproxy.service.ProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final ProxyService proxyService;
    private final MessageTranslationService translationService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProxyController(ProxyService proxyService, MessageTranslationService translationService) {
        this.proxyService = proxyService;
        this.translationService = translationService;
    }

    @PostMapping("/v1/messages")
    public ResponseEntity<StreamingResponseBody> messages(HttpServletRequest request, @RequestBody byte[] body) {
        try {
            JsonNode parsed = mapper.readTree(body);
            boolean streaming = parsed.path("stream").asBoolean(false);
            String requestModel = parsed.path("model").asText(null);

            log.debug("POST /v1/messages [model={}, stream={}]", requestModel, streaming);

            byte[] openAiBody = translationService.translateRequest(body);

            if (log.isTraceEnabled()) {
                log.trace("Anthropic request:\n{}", new String(body, StandardCharsets.UTF_8));
                log.trace("Translated OpenAI request:\n{}", new String(openAiBody, StandardCharsets.UTF_8));
            }

            HttpResponse<InputStream> upstream = proxyService.proxyRaw("/chat/completions", request, openAiBody);
            log.debug("Copilot responded: HTTP {}", upstream.statusCode());

            if (upstream.statusCode() >= 400) {
                return buildErrorResponse(upstream, streaming);
            }

            if (streaming) {
                return buildStreamingResponse(upstream, requestModel);
            } else {
                return buildNonStreamingResponse(upstream, requestModel);
            }
        } catch (IllegalStateException e) {
            log.error("Proxy/auth error: {}", e.getMessage());
            int status = e.getMessage() != null && e.getMessage().contains("401") ? 401 : 502;
            String type = status == 401 ? "authentication_error" : "api_error";
            return anthropicError(status, type, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in /v1/messages", e);
            return anthropicError(500, "api_error", e.getMessage() != null ? e.getMessage() : "Internal server error");
        }
    }

    @GetMapping("/v1/models")
    public ResponseEntity<StreamingResponseBody> models(HttpServletRequest request) {
        return proxyService.proxy("/models", request);
    }

    private ResponseEntity<StreamingResponseBody> buildNonStreamingResponse(
            HttpResponse<InputStream> upstream, String requestModel) {
        try {
            byte[] rawBody = upstream.body().readAllBytes();
            byte[] anthropicBody = translationService.translateResponse(rawBody, requestModel);

            if (log.isTraceEnabled()) {
                log.trace("OpenAI response:\n{}", new String(rawBody, StandardCharsets.UTF_8));
                log.trace("Translated Anthropic response:\n{}", new String(anthropicBody, StandardCharsets.UTF_8));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            StreamingResponseBody stream = out -> out.write(anthropicBody);
            return ResponseEntity.status(200).headers(headers).body(stream);
        } catch (IOException e) {
            log.error("Failed to translate non-streaming response", e);
            return anthropicError(500, "internal_error", "Failed to translate response");
        }
    }

    private ResponseEntity<StreamingResponseBody> buildStreamingResponse(
            HttpResponse<InputStream> upstream, String requestModel) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("content-type", "text/event-stream");
        headers.set("cache-control", "no-cache");

        StreamingResponseBody stream = out -> {
            MessageTranslationService.StreamTranslator translator = translationService.newStreamTranslator(
                    requestModel != null ? requestModel : "unknown");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(upstream.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean tracing = log.isTraceEnabled();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (tracing) log.trace("OpenAI SSE chunk: {}", data);
                        String events = translator.processChunk(data);
                        if (events != null && !events.isEmpty()) {
                            if (tracing) log.trace("Anthropic SSE events:\n{}", events);
                            out.write(events.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                }
            } catch (IOException e) {
                if (e.getCause() instanceof InterruptedException) {
                    log.debug("Stream closed (client disconnect or timeout)");
                } else {
                    log.warn("Stream interrupted: {}", e.getMessage());
                }
            }
        };

        return ResponseEntity.status(200).headers(headers).body(stream);
    }

    private ResponseEntity<StreamingResponseBody> buildErrorResponse(
            HttpResponse<InputStream> upstream, boolean streaming) {
        try {
            byte[] rawBody = upstream.body().readAllBytes();
            String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
            int status = upstream.statusCode();
            log.debug("Upstream error: HTTP {} — {}", status, bodyStr);

            String errorType = switch (status) {
                case 401 -> "authentication_error";
                case 403 -> "permission_error";
                case 404 -> "not_found_error";
                case 429 -> "rate_limit_error";
                case 500, 502, 503 -> "api_error";
                default -> "api_error";
            };

            String message = bodyStr.isBlank() ? "Upstream returned " + status : bodyStr;
            return anthropicError(status, errorType, message);
        } catch (IOException e) {
            return anthropicError(502, "api_error", "Failed to read upstream error");
        }
    }

    private ResponseEntity<StreamingResponseBody> anthropicError(int status, String type, String message) {
        try {
            var error = mapper.createObjectNode();
            error.put("type", "error");
            var errorBody = mapper.createObjectNode();
            errorBody.put("type", type);
            errorBody.put("message", message);
            error.set("error", errorBody);
            byte[] bytes = mapper.writeValueAsBytes(error);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            StreamingResponseBody stream = out -> out.write(bytes);
            return ResponseEntity.status(status).headers(headers).body(stream);
        } catch (IOException e) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            StreamingResponseBody stream = out -> out.write(
                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal error\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.status(500).headers(headers).body(stream);
        }
    }
}
