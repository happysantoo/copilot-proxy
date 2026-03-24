package com.copiproxy.web;

import com.copiproxy.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class ProxyController {
    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(HttpServletRequest request, @RequestBody byte[] body) {
        return proxyService.proxy("/chat/completions", request, body);
    }

    @GetMapping("/models")
    public ResponseEntity<StreamingResponseBody> models(HttpServletRequest request) {
        return proxyService.proxy("/models", request, null);
    }
}
