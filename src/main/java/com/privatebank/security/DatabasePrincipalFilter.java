package com.privatebank.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DatabasePrincipalFilter extends OncePerRequestFilter {

    public static final String NEW_TOKEN_HEADER = "X-New-Access-Token";
    public static final String TOKEN_EXPIRES_AT_HEADER = "X-Token-Expires-At";

    private final CurrentUserService currentUserService;
    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            CurrentUserPrincipal principal = currentUserService.load(jwt.getSubject());
            var authority = new SimpleGrantedAuthority("ROLE_" + principal.role().name());
            var databaseAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, jwt.getTokenValue(), List.of(authority));
            databaseAuthentication.setDetails(jwtAuthentication.getDetails());
            SecurityContextHolder.getContext().setAuthentication(databaseAuthentication);

            if (jwtService.shouldRenew(jwt)) {
                JwtService.IssuedToken renewed = jwtService.issue(principal.userId());
                response.setHeader(NEW_TOKEN_HEADER, renewed.value());
                response.setHeader(TOKEN_EXPIRES_AT_HEADER, renewed.expiresAt().toString());
            }
        }
        filterChain.doFilter(request, response);
    }
}
