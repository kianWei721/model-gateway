package com.modelgateway.api;

import com.modelgateway.core.GatewayException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> gateway(GatewayException exception) {
        HttpHeaders headers = new HttpHeaders();
        exception.headers().forEach(headers::add);
        return ResponseEntity.status(exception.status())
                .headers(headers)
                .body(ErrorBodies.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorBodies.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ErrorBodies.of("INVALID_JSON", "Request body must be a JSON object"));
    }
}
