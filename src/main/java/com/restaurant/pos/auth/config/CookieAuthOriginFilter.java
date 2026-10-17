package com.restaurant.pos.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * CSRF defence for cookie-authenticated requests.
 * <p>
 * Auth cookies are {@code SameSite=None} in production, so a browser attaches them to requests
 * started from any website. A state-changing request that relies on those cookies (no
 * {@code Authorization: Bearer} header) is therefore only accepted when its {@code Origin}
 * (or {@code Referer}) belongs to a trusted frontend. Browsers always send {@code Origin} on
 * cross-site POST/PUT/PATCH/DELETE, and a page cannot forge it.
 * <p>
 * Requests carrying a Bearer header are not affected: a forged cross-site request cannot set that header.
 * The trusted list is deliberately stricter than the CORS list (no wildcard Vercel / Pages hosts,
 * which anyone can register).
 */
@Slf4j
public class CookieAuthOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> AUTH_COOKIES = Set.of("access_token", "token", "refresh_token");

    private final CorsConfiguration trusted = new CorsConfiguration();

    public CookieAuthOriginFilter(List<String> trustedOriginPatterns) {
        trustedOriginPatterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .forEach(p -> trusted.addAllowedOriginPattern(p.trim()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/public/") || path.startsWith("/api/delivery/")) {
            return true; // unauthenticated endpoints: nothing for a forged request to ride on
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return true;
        }
        return !hasAuthCookie(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String originHeader = request.getHeader("Origin");
        String refererHeader = request.getHeader("Referer");

        // Neither header: not a cross-site browser request (browsers always send one of them), so allow.
        if (isBlank(originHeader) && isBlank(refererHeader)) {
            chain.doFilter(request, response);
            return;
        }

        String origin = !isBlank(originHeader) ? originHeader : originOf(refererHeader);
        if (origin != null && trusted.checkOrigin(origin) != null) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Blocked cookie-authenticated {} {} from untrusted origin '{}'",
                request.getMethod(), request.getRequestURI(), origin);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Cross-site request blocked\"}");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean hasAuthCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie c : cookies) {
            if (AUTH_COOKIES.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String originOf(String referer) {
        try {
            URI u = URI.create(referer);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
