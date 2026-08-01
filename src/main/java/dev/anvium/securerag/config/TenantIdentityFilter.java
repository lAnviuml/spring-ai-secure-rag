package dev.anvium.securerag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TenantIdentityFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = request.getHeader("X-Tenant-Id");
        String principalId = request.getHeader("X-Principal-Id");
        if (!valid(tenantId) || !valid(principalId)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Valid tenant and principal headers are required");
            return;
        }
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        TenantContext.set(new TenantContext.Identity(tenantId, principalId));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("requestId");
        }
    }

    private boolean valid(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }
}
