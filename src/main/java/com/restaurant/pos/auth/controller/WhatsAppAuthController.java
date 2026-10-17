package com.restaurant.pos.auth.controller;

import com.restaurant.pos.auth.dto.AuthResponse;
import com.restaurant.pos.auth.service.WhatsAppAuthService;
import com.restaurant.pos.auth.util.AuthCookieUtil;
import com.restaurant.pos.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth/whatsapp")
@RequiredArgsConstructor
public class WhatsAppAuthController {

    private final WhatsAppAuthService whatsAppAuthService;
    private final AuthCookieUtil cookieUtil;

    @Data
    public static class PhoneOtpRequest {
        private String phone;
        private String otp;
    }

    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<String>> sendOtp(@RequestBody Map<String, String> payload) {
        String phone = payload.get("phone");
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Phone number is required"));
        }
        boolean sent = whatsAppAuthService.sendClientOtp(phone);
        if (sent) {
            return ResponseEntity.ok(ApiResponse.success("Verification code sent to WhatsApp: " + phone));
        } else {
            return ResponseEntity.status(503).body(ApiResponse.error("WhatsApp gateway is busy or offline. Please try again or use Email."));
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyOtp(
            @RequestBody PhoneOtpRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        if (request.getPhone() == null || request.getOtp() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Phone and OTP are required"));
        }
        String ipAddress = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");

        Map<String, Object> result = whatsAppAuthService.verifyClientOtp(
                request.getPhone(), request.getOtp(), ipAddress, userAgent
        );

        if (Boolean.TRUE.equals(result.get("exists")) && result.get("auth") instanceof AuthResponse authResponse) {
            cookieUtil.createAuthCookies(servletRequest, servletResponse, authResponse.getAccessToken(), authResponse.getRefreshToken());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
