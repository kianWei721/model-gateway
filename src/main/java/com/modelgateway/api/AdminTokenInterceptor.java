package com.modelgateway.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards /admin with a static token. The admin API is off entirely when no token is configured. */
public final class AdminTokenInterceptor implements HandlerInterceptor {

    private final byte[] token;

    public AdminTokenInterceptor(String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String presented = request.getHeader("X-Admin-Token");
        boolean allowed =
                token.length > 0
                        && presented != null
                        && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
        if (!allowed) {
            response.setStatus(token.length == 0 ? 404 : 401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"type\":\"gateway_error\",\"code\":\"ADMIN_UNAUTHORIZED\",\"message\":\"admin token required\"}}");
            return false;
        }
        return true;
    }
}
