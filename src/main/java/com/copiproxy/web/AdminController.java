package com.copiproxy.web;

import com.copiproxy.model.ApiKeyRecord;
import com.copiproxy.service.ApiKeyService;
import com.copiproxy.service.GithubCopilotService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api-keys")
public class AdminController {
    private final ApiKeyService apiKeyService;
    private final GithubCopilotService githubCopilotService;

    public AdminController(ApiKeyService apiKeyService, GithubCopilotService githubCopilotService) {
        this.apiKeyService = apiKeyService;
        this.githubCopilotService = githubCopilotService;
    }

    @GetMapping
    public List<ApiKeyRecord> list() {
        return apiKeyService.list();
    }

    @PostMapping
    public ApiKeyRecord create(@RequestBody CreateApiKeyRequest req) {
        return apiKeyService.create(req.name(), req.key());
    }

    @PatchMapping("/{id}")
    public ApiKeyRecord update(@PathVariable String id, @RequestBody UpdateApiKeyRequest req) {
        return apiKeyService.updateName(id, req.name());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        apiKeyService.delete(id);
    }

    @PostMapping("/default")
    public ApiKeyRecord setDefault(@RequestBody SetDefaultRequest req) {
        return apiKeyService.setDefault(req.id());
    }

    @PostMapping("/{id}/refresh-meta")
    public ApiKeyRecord refresh(@PathVariable String id) {
        return apiKeyService.refreshMeta(id);
    }

    @GetMapping(value = "/device-flow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deviceFlow() {
        SseEmitter emitter = new SseEmitter(300_000L);
        Thread.startVirtualThread(() -> runDeviceFlow(emitter));
        return emitter;
    }

    private void runDeviceFlow(SseEmitter emitter) {
        try {
            Map<String, Object> init = githubCopilotService.initiateDeviceFlow();
            String deviceCode = asString(init.get("device_code"));
            String userCode = asString(init.get("user_code"));
            String verificationUri = asString(init.get("verification_uri"));
            long expiresIn = asLong(init.get("expires_in"), 900L);
            long expiresAt = Instant.now().toEpochMilli() + (expiresIn * 1000);
            send(emitter, "initiated", Map.of(
                    "type", "initiated",
                    "deviceCode", deviceCode,
                    "userCode", userCode,
                    "verificationUri", verificationUri,
                    "expiresAt", expiresAt,
                    "message", "Device flow initiated. Please authorize in browser."
            ));

            long deadline = Instant.now().toEpochMilli() + (expiresIn * 1000);
            while (Instant.now().toEpochMilli() < deadline) {
                Thread.sleep(5500);
                Map<String, Object> event = githubCopilotService.verifyDeviceFlow(deviceCode);
                String error = asString(event.get("error"));
                if ("authorization_pending".equals(error)) {
                    send(emitter, "pending", Map.of("type", "pending", "message", "Waiting for user authorization..."));
                    continue;
                }
                if (error != null && !error.isBlank()) {
                    send(emitter, "error", Map.of("type", "error", "message", asString(event.get("error_description"))));
                    emitter.complete();
                    return;
                }
                String accessToken = asString(event.get("access_token"));
                ApiKeyRecord created = apiKeyService.create("DeviceFlow-" + System.currentTimeMillis(), accessToken);
                send(emitter, "success", Map.of(
                        "type", "success",
                        "message", "Authorization successful",
                        "apiKeyId", created.id()
                ));
                emitter.complete();
                return;
            }

            send(emitter, "error", Map.of("type", "error", "message", "Device flow timed out"));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> payload) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(payload));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return defaultValue;
    }

    public record CreateApiKeyRequest(String name, String key) {}
    public record UpdateApiKeyRequest(String name) {}
    public record SetDefaultRequest(String id) {}
}
