package com.restaurant.pos.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.auth.service.JwtService;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.tenant.TenantContext;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        String path = request.getServletPath();
        String method = request.getMethod();
        String authHeader = request.getHeader("Authorization");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        StringBuilder cookiesLog = new StringBuilder();
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                cookiesLog.append(c.getName()).append("=");
                String val = c.getValue();
                cookiesLog.append(val.substring(0, Math.min(val.length(), 10))).append("...; ");
            }
        }
        
        System.out.println("===> [DEBUG LOG-V3] JWT Filter: Method=" + method + ", Path=" + path + ", Auth=" + (authHeader != null ? "Present" : "null") + ", Cookies=[" + cookiesLog + "]");

        // Skip filter for public auth endpoints that don't use tokens (e.g. login, signup)
        if (isPublicPath(path) && authHeader == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
            System.out.println("===> [DEBUG] JWT Filter: Extracted from Authorization header");
        } else {
            // Check for access_token or token cookie
            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    if ("access_token".equals(cookie.getName()) || "token".equals(cookie.getName())) {
                        jwt = cookie.getValue();
                        System.out.println("===> [DEBUG] JWT Filter: Extracted from cookie: " + cookie.getName());
                        break;
                    }
                }
                if (jwt == null) {
                    String names = java.util.Arrays.stream(request.getCookies())
                        .map(jakarta.servlet.http.Cookie::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                    System.out.println("===> [DEBUG] JWT Filter: No matching auth cookie found. Present cookies: " + names);
                }
            } else {
                System.out.println("===> [DEBUG] JWT Filter: No cookies present in request");
            }
        }

        if (jwt == null) {
            System.out.println("===> [DEBUG] JWT Filter: No JWT found in header or cookies. Proceeding without authentication.");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            System.out.println("===> [DEBUG] JWT Filter: Found JWT (first 15 chars): " + jwt.substring(0, Math.min(jwt.length(), 15)) + "...");
            final String userEmail = jwtService.extractUsername(jwt);
            System.out.println("===> [DEBUG] JWT Filter: Extracted user: " + userEmail);
            
            // Extract clientId and orgId from JWT claims
            try {
                String clientIdStr = jwtService.extractClaim(jwt, claims -> {
                    Object cid = claims.get("clientId");
                    return cid != null ? cid.toString() : null;
                });
                if (clientIdStr != null) {
                    TenantContext.setCurrentTenant(UUID.fromString(clientIdStr));
                    System.out.println("===> [DEBUG] JWT Filter: Set Tenant via clientId: " + clientIdStr);
                }

                String orgIdStr = jwtService.extractClaim(jwt, claims -> {
                    Object oid = claims.get("orgId");
                    return oid != null ? oid.toString() : null;
                });
                if (orgIdStr != null) {
                    TenantContext.setCurrentOrg(UUID.fromString(orgIdStr));
                    System.out.println("===> [DEBUG] JWT Filter: Set Org via orgId: " + orgIdStr);
                }
            } catch (Exception e) {
                System.out.println("===> [DEBUG] JWT Filter: Error extracting claims: " + e.getMessage());
            }

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
                System.out.println("===> [DEBUG] JWT Filter: Loaded user: " + userEmail + " with roles: " + userDetails.getAuthorities());

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    System.out.println("===> [DEBUG] JWT Filter: SUCCESS - Authentication set");
                } else {
                    System.out.println("===> [DEBUG] JWT Filter: Token invalid for user: " + userEmail);
                }
            }
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            System.out.println("===> [DEBUG] JWT Filter: Token expired: " + e.getMessage());
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
            } else {
                sendErrorResponse(request, response, "JWT token has expired", HttpServletResponse.SC_UNAUTHORIZED);
            }
        } catch (MalformedJwtException e) {
            System.out.println("===> [DEBUG] JWT Filter: Malformed token: " + e.getMessage());
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
            } else {
                sendErrorResponse(request, response, "Invalid JWT token", HttpServletResponse.SC_UNAUTHORIZED);
            }
        } catch (SignatureException e) {
            System.out.println("===> [DEBUG] JWT Filter: Invalid signature: " + e.getMessage());
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
            } else {
                sendErrorResponse(request, response, "JWT signature match failed", HttpServletResponse.SC_UNAUTHORIZED);
            }
        } catch (Exception e) {
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
                return;
            }
            if (isClientDisconnect(e)) {
                log.debug("Client disconnected before authentication response completed: {}", e.getMessage());
                return;
            }
            log.error("JWT authentication failed", e);
            if (!response.isCommitted()) {
                try {
                    sendErrorResponse(request, response,
                            "Authentication failed: " + e.getMessage(),
                            HttpServletResponse.SC_UNAUTHORIZED);
                } catch (IOException writeFailure) {
                    if (isClientDisconnect(writeFailure)) {
                        log.debug("Client disconnected while writing authentication failure: {}",
                                writeFailure.getMessage());
                        return;
                    }
                    throw writeFailure;
                }
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response, String message, int status) throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        
        ApiResponse<Object> apiResponse = ApiResponse.error(message);
        try {
            response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
        } catch (IOException writeFailure) {
            if (isClientDisconnect(writeFailure)) {
                log.debug("Client disconnected while writing authentication response: {}",
                        writeFailure.getMessage());
                return;
            }
            throw writeFailure;
        }
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase();
            if (className.contains("ClientAbortException")
                    || message.contains("broken pipe")
                    || message.contains("connection reset by peer")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isPublicPath(String path) {
        if (path == null) return false;
        if (path.contains("/accept-terms")) return false;
        return path.startsWith("/api/v1/auth/") ||
               path.startsWith("/api/v1/debug") ||
               path.startsWith("/api/v1/public") ||
               path.startsWith("/api/delivery") ||
               path.startsWith("/delivery") ||
               path.startsWith("/actuator") ||
               path.equals("/v2/api-docs") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/swagger");
    }
}
