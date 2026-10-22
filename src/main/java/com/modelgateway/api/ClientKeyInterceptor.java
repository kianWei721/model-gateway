package com.modelgateway.api;

import com.modelgateway.auth.ClientKey;
import com.modelgateway.auth.ClientKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

/** Resolves {@code Authorization: Bearer gk-...} to a client key for everything under /v1. */
public final class ClientKeyInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "gateway.clientKey";

    private final ClientKeyService clientKeys;
    private final JsonMapper jsonMapper;

    public ClientKeyInterceptor(ClientKeyService clientKeys, JsonMapper jsonMapper) {
        this.clientKeys = clientKeys;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authorization = request.getHeader("Authorization");
        Optional<ClientKey> key =
                authorization != null && authorization.startsWith("Bearer ")
                        ? clientKeys.authenticate(authorization.substring(7).trim())
                        : Optional.empty();
        if (key.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(
                    jsonMapper.writeValueAsString(ErrorBodies.of("INVALID_API_KEY", "Missing, unknown or revoked gateway key")));
            return false;
        }
        request.setAttribute(ATTRIBUTE, key.get());
        return true;
    }
}
