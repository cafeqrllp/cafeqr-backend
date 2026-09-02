package com.restaurant.pos.auth.controller;

import com.restaurant.pos.auth.service.OtpService;
import com.restaurant.pos.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Public auth endpoints consumed by the CafeQR Delivery Website / App.
 *
 * These endpoints do NOT require a staff JWT — they are for end-customers
 * authenticating with email OTP.
 *
 * Endpoints
 * ---------
 *  POST /api/v1/auth/customer/verify-otp
 *      Verifies a 6-digit OTP that was previously requested via
 *      POST /api/v1/auth/send-otp.
 *
 *      The OTP is stored in Redis (with fallback to in-memory) by OtpService.
 *      On success the frontend (Next.js) issues its own HttpOnly session cookie
 *      (HMAC-SHA256 via lib/auth.js) — this endpoint simply returns
 *      { verified: true, email } so the frontend knows the OTP was accepted.
 *
 * Request  { "email": "user@example.com", "otp": "123456" }
 * Response 200  { "success": true, "data": { "verified": true, "email": "..." } }
 * Response 400  { "success": false, "message": "Invalid or expired OTP" }
 */
@RestController
@RequestMapping("/api/v1/auth/customer")
@RequiredArgsConstructor
public class CustomerAuthController {

    private final OtpService otpService;
    private final com.restaurant.pos.purchasing.repository.CustomerRepository customerRepository;
    private final com.restaurant.pos.client.repository.ClientRepository clientRepository;
    private final com.restaurant.pos.client.repository.OrganizationRepository organizationRepository;

    // ── Request body DTO ──────────────────────────────────────────────────────

    @Data
    public static class VerifyOtpRequest {
        @Email(message = "Invalid email address")
        @NotBlank(message = "Email is required")
        private String email;

        @NotBlank(message = "OTP is required")
        private String otp;

        private UUID clientId;
        private UUID orgId;
        private String name;
        private String phone;
    }

    // ── POST /api/v1/auth/customer/verify-otp ─────────────────────────────────

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        boolean valid = otpService.verifyOtp(request.getEmail(), request.getOtp());

        if (!valid) {
            return ResponseEntity
                    .status(400)
                    .body(ApiResponse.error("Invalid or expired OTP. Please request a new one."));
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        UUID clientId = request.getClientId();
        UUID orgId = request.getOrgId();

        // Resolve clientId if orgId or client ID were swapped
        if (clientId != null) {
            var clientOpt = clientRepository.findById(clientId);
            if (clientOpt.isEmpty()) {
                var orgOpt = organizationRepository.findById(clientId);
                if (orgOpt.isPresent()) {
                    clientId = orgOpt.get().getClientId();
                    if (orgId == null) {
                        orgId = orgOpt.get().getId();
                    }
                }
            }
        }

        com.restaurant.pos.purchasing.domain.Customer customer = null;
        if (clientId != null) {
            var existing = customerRepository.findByEmailAndClientId(normalizedEmail, clientId);
            if (existing.isPresent()) {
                customer = existing.get();
                boolean changed = false;
                if ((customer.getName() == null || customer.getName().isBlank() || "Guest".equalsIgnoreCase(customer.getName()))
                        && request.getName() != null && !request.getName().isBlank()) {
                    customer.setName(request.getName().trim());
                    changed = true;
                }
                if ((customer.getPhone() == null || customer.getPhone().isBlank())
                        && request.getPhone() != null && !request.getPhone().isBlank()) {
                    customer.setPhone(normalizePhone(request.getPhone()));
                    changed = true;
                }
                if (changed) {
                    customer = customerRepository.save(customer);
                }
            } else {
                String initialName = (request.getName() != null && !request.getName().isBlank())
                        ? request.getName().trim()
                        : normalizedEmail.split("@")[0];
                customer = com.restaurant.pos.purchasing.domain.Customer.builder()
                        .name(initialName)
                        .email(normalizedEmail)
                        .phone(normalizePhone(request.getPhone()))
                        .customerCategory("REGULAR")
                        .isactive("Y")
                        .build();
                customer.setClientId(clientId);
                customer.setOrgId(null); // Customers are global to the client/tenant
                customer = customerRepository.save(customer);
            }
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("verified", true);
        payload.put("email", normalizedEmail);
        if (customer != null) {
            payload.put("customerId", customer.getId());
            payload.put("name", customer.getName());
            payload.put("phone", customer.getPhone() != null ? customer.getPhone() : "");
            payload.put("address", customer.getAddress() != null ? customer.getAddress() : "");
        }

        return ResponseEntity.ok(ApiResponse.success(payload));
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String normalized = phone.trim().replaceAll("[\\s()\\-]", "");
        return normalized.isBlank() ? null : normalized;
    }
}
