package com.restaurant.pos.auth.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieUtil {

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    public void createAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        createAuthCookies(null, response, accessToken, refreshToken);
    }

    public void createAuthCookies(HttpServletRequest request, HttpServletResponse response, String accessToken, String refreshToken) {
        long accessMaxAge = (jwtExpiration / 1000) + 300; // JWT + 5 minutes
        long refreshMaxAge = refreshExpiration / 1000;

        boolean isSecure = isSecureRequest(request);
        String sameSitePolicy = isSecure ? "SameSite=None; Secure; Partitioned" : "SameSite=Lax";

        String accessCookieHeader = String.format(
            "access_token=%s; Path=/; HttpOnly; %s; Max-Age=%d",
            accessToken, sameSitePolicy, accessMaxAge
        );

        String tokenAliasHeader = String.format(
            "token=%s; Path=/; HttpOnly; %s; Max-Age=%d",
            accessToken, sameSitePolicy, accessMaxAge
        );

        String refreshCookieHeader = String.format(
            "refresh_token=%s; Path=/; HttpOnly; %s; Max-Age=%d",
            refreshToken, sameSitePolicy, refreshMaxAge
        );

        response.addHeader("Set-Cookie", accessCookieHeader);
        response.addHeader("Set-Cookie", tokenAliasHeader);
        response.addHeader("Set-Cookie", refreshCookieHeader);

        System.out.println("===> [DEBUG] AuthCookieUtil: Set-Cookie headers added (" + sameSitePolicy + ")");
    }

    public void clearAuthCookies(HttpServletResponse response) {
        clearAuthCookies(null, response);
    }

    public void clearAuthCookies(HttpServletRequest request, HttpServletResponse response) {
        boolean isSecure = isSecureRequest(request);
        String sameSitePolicy = isSecure ? "SameSite=None; Secure; Partitioned" : "SameSite=Lax";

        response.addHeader("Set-Cookie", "access_token=; Path=/; HttpOnly; " + sameSitePolicy + "; Max-Age=0");
        response.addHeader("Set-Cookie", "token=; Path=/; HttpOnly; " + sameSitePolicy + "; Max-Age=0");
        response.addHeader("Set-Cookie", "refresh_token=; Path=/; HttpOnly; " + sameSitePolicy + "; Max-Age=0");

        System.out.println("===> [DEBUG] AuthCookieUtil: Set-Cookie headers added to CLEAR cookies (" + sameSitePolicy + ")");
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request == null) {
            return true; // Default to secure/SameSite=None when request object isn't directly passed
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        if ("https".equalsIgnoreCase(proto) || request.isSecure()) {
            return true;
        }
        if (origin != null && origin.startsWith("https://")) {
            return true;
        }
        if (referer != null && referer.startsWith("https://")) {
            return true;
        }
        return false;
    }
}

