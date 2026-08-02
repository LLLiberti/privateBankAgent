package com.privatebank.common.web;

import com.privatebank.security.CurrentUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = authentication != null && authentication.getPrincipal() instanceof CurrentUserPrincipal principal
                    ? principal.userId()
                    : "anonymous";
            log.info("audit traceId={} userId={} method={} path={} status={} durationMs={} clientIp={} userAgent={}",
                    MDC.get("traceId"), userId, request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000,
                    request.getRemoteAddr(), sanitize(request.getHeader("User-Agent")));
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[\\r\\n]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 256));
    }
}
