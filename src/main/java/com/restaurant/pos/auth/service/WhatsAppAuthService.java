package com.restaurant.pos.auth.service;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.dto.AuthResponse;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.whatsapp.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppAuthService {

    private final OtpService otpService;
    private final WhatsAppService whatsAppService;
    private final UserRepository userRepository;
    private final AuthService authService;

    private static final String CLIENT_OTP_PREFIX = "WA_CLIENT_";
    private static final String CUST_OTP_PREFIX = "WA_CUST_";

    public String normalizePhone(String rawPhone) {
        if (rawPhone == null) return null;
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "91" + digits; // Default country code for 10-digit Indian numbers
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            return "91" + digits.substring(1);
        }
        return digits;
    }

    /**
     * Send WhatsApp OTP to a Client (Owner / Staff) for login or signup verification
     */
    public boolean sendClientOtp(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        if (phone == null || phone.length() < 10) {
            throw new BusinessException("Invalid phone number format");
        }
        String otp = otpService.generateAndSaveOtp(CLIENT_OTP_PREFIX + phone);
        log.info("[WhatsAppAuth] Generated client OTP for {}: {}", phone, otp);
        return whatsAppService.sendOfficialAuthOtp(phone, otp);
    }

    /**
     * Verify WhatsApp OTP for Client. If user exists, returns full AuthResponse (logged in).
     * If user does not exist yet (signing up), returns { verified: true, phone, exists: false }.
     */
    public Map<String, Object> verifyClientOtp(String rawPhone, String otp, String ipAddress, String userAgent) {
        String phone = normalizePhone(rawPhone);
        if (phone == null || phone.length() < 10) {
            throw new BusinessException("Invalid phone number");
        }
        boolean valid = otpService.verifyOtp(CLIENT_OTP_PREFIX + phone, otp);
        if (!valid) {
            throw new BusinessException("Invalid or expired OTP");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("verified", true);
        result.put("phone", phone);

        // Check if user already exists
        String last10 = phone.length() >= 10 ? phone.substring(phone.length() - 10) : phone;
        Optional<User> userOpt = userRepository.findFirstByPhone(phone);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findFirstByPhone(last10);
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            AuthResponse authResponse = authService.authenticateDirectly(user, ipAddress, userAgent);
            result.put("exists", true);
            result.put("auth", authResponse);
        } else {
            result.put("exists", false);
            result.put("message", "Phone verified. Please complete signup details.");
        }

        return result;
    }

    /**
     * Send WhatsApp OTP to a customer (Table QR / Delivery)
     */
    public boolean sendCustomerOtp(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        if (phone == null || phone.length() < 10) {
            throw new BusinessException("Invalid phone number format");
        }
        String otp = otpService.generateAndSaveOtp(CUST_OTP_PREFIX + phone);
        log.info("[WhatsAppAuth] Generated customer OTP for {}: {}", phone, otp);
        return whatsAppService.sendOfficialAuthOtp(phone, otp);
    }

    /**
     * Verify WhatsApp OTP for customer
     */
    public boolean verifyCustomerOtp(String rawPhone, String otp) {
        String phone = normalizePhone(rawPhone);
        if (phone == null || phone.length() < 10) {
            return false;
        }
        // Verify with CUST_OTP_PREFIX first, fallback to regular sanitized key
        boolean valid = otpService.verifyOtp(CUST_OTP_PREFIX + phone, otp);
        if (!valid) {
            valid = otpService.verifyOtp(phone, otp);
        }
        if (!valid && rawPhone.length() >= 10) {
            String last10 = rawPhone.substring(rawPhone.length() - 10);
            valid = otpService.verifyOtp(CUST_OTP_PREFIX + last10, otp) || otpService.verifyOtp(last10, otp);
        }
        return valid;
    }
}
