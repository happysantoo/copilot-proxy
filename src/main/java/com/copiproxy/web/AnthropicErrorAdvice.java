package com.copiproxy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Ensures every error response leaving the proxy is in Anthropic Messages API
 * format so that Claude Code (and other Anthropic clients) can parse it instead
 * of seeing Spring's default Whitelabel error page.
 */
@ControllerAdvice
public class AnthropicErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(AnthropicErrorAdvice.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> handleNotFound(NoResourceFoundException ex) {
        log.debug("No endpoint matched: {}", ex.getMessage());
        return anthropicError(404, "not_found_error",
                "The requested endpoint is not supported by this proxy. " + ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<byte[]> handleIllegalState(IllegalStateException ex) {
        log.error("Proxy error: {}", ex.getMessage());
        boolean isAuth = ex.getMessage() != null && ex.getMessage().contains("401");
        int status = isAuth ? 401 : 502;
        String type = isAuth ? "authentication_error" : "api_error";
        return anthropicError(status, type, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return anthropicError(500, "api_error",
                ex.getMessage() != null ? ex.getMessage() : "Internal server error");
    }

    private ResponseEntity<byte[]> anthropicError(int status, String type, String message) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "error");
            ObjectNode error = mapper.createObjectNode();
            error.put("type", type);
            error.put("message", message);
            root.set("error", error);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(root));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal error\"}}"
                            .getBytes());
        }
    }
}
