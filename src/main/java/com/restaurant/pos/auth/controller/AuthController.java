package com.restaurant.pos.auth.controller;

import com.restaurant.pos.auth.dto.AuthRequest;
import com.restaurant.pos.auth.dto.AuthResponse;
import com.restaurant.pos.auth.dto.RegisterRequest;
import com.restaurant.pos.auth.dto.SendOtpRequest;
import com.restaurant.pos.auth.service.AuthService;
import com.restaurant.pos.auth.service.EmailService;
import com.restaurant.pos.auth.service.OtpService;
import com.restaurant.pos.auth.util.AuthCookieUtil;
import com.restaurant.pos.common.dto.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService service;
    private final EmailService emailService;
    private final OtpService otpService;
    private final AuthCookieUtil cookieUtil;

    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<String>> sendOtp(
            @Valid @RequestBody SendOtpRequest request
    ) {
        String otp = otpService.generateAndSaveOtp(request.getEmail());
        emailService.sendOtpEmail(request.getEmail(), otp);
        return ResponseEntity.ok(ApiResponse.success("OTP sent successfully to " + request.getEmail()));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        String ipAddress = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");
        AuthResponse response = service.register(request, ipAddress, userAgent);
        cookieUtil.createAuthCookies(servletResponse, response.getAccessToken(), response.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/authenticate")
    public ResponseEntity<ApiResponse<AuthResponse>> authenticate(
            @RequestBody AuthRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        String ipAddress = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");
        AuthResponse response = service.authenticate(request, ipAddress, userAgent);
        cookieUtil.createAuthCookies(servletResponse, response.getAccessToken(), response.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = getRefreshToken(request);
        if (refreshToken == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Refresh token missing"));
        }

        try {
            String ipAddress = request.getRemoteAddr();
            String userAgent = request.getHeader("User-Agent");
            AuthResponse authResponse = service.refreshToken(refreshToken, ipAddress, userAgent);
            cookieUtil.createAuthCookies(response, authResponse.getAccessToken(), authResponse.getRefreshToken());
            return ResponseEntity.ok(ApiResponse.success(authResponse));
        } catch (Exception e) {
            // Any refresh failure (expired, revoked, invalid) => 401 so frontend redirects to login
            cookieUtil.clearAuthCookies(response);
            return ResponseEntity.status(401).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = getRefreshToken(request);
        if (refreshToken != null) {
            service.logout(refreshToken);
        }
        cookieUtil.clearAuthCookies(response);
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully"));
    }

    /**
     * Extract the refresh token from the request. Checks the HttpOnly cookie
     * first (same-domain deployments), then falls back to the Authorization
     * header (cross-domain deployments where browsers block third-party cookies).
     */
    private String getRefreshToken(HttpServletRequest request) {
        // 1. Try the HttpOnly cookie (works when frontend & backend share the same domain)
        String fromCookie = getCookieValue(request, "refresh_token");
        if (fromCookie != null) {
            return fromCookie;
        }
        // 2. Fall back to Authorization header (cross-domain: Vercel/Cloudflare → Hugging Face/Render)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<String>> changePassword(
            @RequestBody com.restaurant.pos.auth.dto.ChangePasswordRequest request
    ) {
        service.changePassword(request.getEmail(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password updated successfully"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<String>> forgotPassword(
            @Valid @RequestBody SendOtpRequest request
    ) {
        if (!service.userExists(request.getEmail())) {
            throw new com.restaurant.pos.common.exception.BusinessException("No account found with this email");
        }
        String otp = otpService.generateAndSaveOtp(request.getEmail());
        emailService.sendOtpEmail(request.getEmail(), otp);
        return ResponseEntity.ok(ApiResponse.success("Verification code sent to " + request.getEmail()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(
            @Valid @RequestBody com.restaurant.pos.auth.dto.ResetPasswordRequest request
    ) {
        service.resetPassword(request.getEmail(), request.getOtp(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password updated successfully"));
    }
}
