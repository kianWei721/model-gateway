package com.modelgateway.api;

import com.modelgateway.auth.ClientKey;
import com.modelgateway.core.GatewayResult;
import com.modelgateway.core.GatewayService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible entry point. {@code model} names a gateway route (light / standard /
 * reasoning), not a vendor model: callers state how hard the task is, the gateway picks who serves.
 *
 * <p>Optional caller headers: {@code X-Gateway-Deadline-Ms} (how long the caller will still wait)
 * and {@code X-Correlation-Id} (the caller's own trace id, stored with every attempt).
 */
@RestController
public class ChatCompletionsController {

    private final GatewayService gateway;

    public ChatCompletionsController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<ObjectNode> complete(
            @RequestBody ObjectNode request,
            @RequestAttribute(ClientKeyInterceptor.ATTRIBUTE) ClientKey key,
            @RequestHeader(name = "X-Gateway-Deadline-Ms", required = false) String deadlineMs,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        GatewayResult result = gateway.complete(key, request, deadlineMs, correlationId);
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach(headers::add);
        return ResponseEntity.ok().headers(headers).body(result.body());
    }
}
