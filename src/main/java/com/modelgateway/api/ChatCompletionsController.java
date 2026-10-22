package com.modelgateway.api;

import com.modelgateway.auth.ClientKey;
import com.modelgateway.core.GatewayException;
import com.modelgateway.core.GatewayResult;
import com.modelgateway.core.GatewayService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible entry point. {@code model} names a gateway route (light / standard /
 * reasoning), not a vendor model: callers state how hard the task is, the gateway picks who serves.
 */
@RestController
public class ChatCompletionsController {

    private final GatewayService gateway;

    public ChatCompletionsController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<ObjectNode> complete(
            @RequestBody ObjectNode request, @RequestAttribute(ClientKeyInterceptor.ATTRIBUTE) ClientKey key) {
        if (!request.path("model").isString() || !request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new GatewayException(400, "INVALID_REQUEST", "model (a route name) and a non-empty messages array are required");
        }
        GatewayResult result = gateway.complete(key, request);
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach(headers::add);
        return ResponseEntity.ok().headers(headers).body(result.body());
    }
}
