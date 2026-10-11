package com.restaurant.pos.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP fixed-window rate limit for unauthenticated endpoints (/api/v1/public/**, /api/v1/founder/**).
 * In-memory, per instance. Limits are generous for public traffic because a whole restaurant can share
 * one IP (venue Wi-Fi); the founder endpoints are limited tightly to stop key guessing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    @Value("${app.rate-limit.public-per-minute:600}")
    private int publicPerMinute;

    @Value("${app.rate-limit.founder-per-minute:10}")
    private int founderPerMinute;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    private static final class Window {
        final long start = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger();
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        // Long-lived SSE streams are capped by their own services, not by request rate.
        return !(path.startsWith("/api/v1/public/") || path.startsWith("/api/v1/founder/"))
                || path.endsWith("/stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean founder = request.getRequestURI().startsWith("/api/v1/founder/");
        int limit = founder ? founderPerMinute : publicPerMinute;
        String key = (founder ? "f:" : "p:") + clientIp(request);

        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, cur) -> cur == null || now - cur.start >= WINDOW_MS ? new Window() : cur);
        if (w.count.incrementAndGet() > limit) {
            long retry = Math.max(1, (WINDOW_MS - (now - w.start)) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retry));
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Too many requests. Please retry shortly.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Behind Caddy/Cloudflare the real client is in a forwarded header; the first hop is the client. */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank()) {
            String xff = request.getHeader("X-Forwarded-For");
            ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr();
        }
        return ip;
    }

    @Scheduled(fixedDelay = 60_000)
    void purgeExpired() {
        long now = System.currentTimeMillis();
        windows.values().removeIf(w -> now - w.start >= WINDOW_MS);
    }
}
