package com.restaurant.pos.founder.lead.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.founder.lead.dto.FounderLeadDto;
import com.restaurant.pos.founder.lead.service.FounderLeadService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/leads")
@RequiredArgsConstructor
@Slf4j
public class PublicLeadController {

    private final FounderLeadService leadService;
    private final com.restaurant.pos.auth.service.EmailService emailService;

    @Data
    public static class PublicLeadInquiryRequest {
        private String name;
        private String businessName;
        private String phone;
        private String email;
        private String city;
        private String source;
        private String notes;
    }

    @Data
    public static class WhatsAppAutoCaptureRequest {
        private String senderPhone;
        private String pushName;
        private String messageText;
        private String gatewayToken;
    }

    @PostMapping
    public ResponseEntity<?> submitPublicInquiry(@RequestBody PublicLeadInquiryRequest req) {
        log.info("[PublicLead] Received public lead inquiry for {}", req.getPhone());
        FounderLeadDto dto = FounderLeadDto.builder()
                .name(req.getName())
                .businessName(req.getBusinessName())
                .primaryPhone(req.getPhone())
                .email(req.getEmail())
                .city(req.getCity())
                .source(req.getSource() != null ? req.getSource() : "WEBSITE")
                .status("NEW_INQUIRY")
                .temperature("WARM")
                .notes(req.getNotes())
                .build();

        FounderLeadDto created = leadService.createLead(dto);

        // Dispatch instant email alert to cafeqrllp@gmail.com
        try {
            emailService.sendLeadInquiryAlert(
                    req.getName(),
                    req.getBusinessName(),
                    req.getPhone(),
                    req.getCity(),
                    req.getNotes()
            );
        } catch (Exception ex) {
            log.warn("[PublicLead] Could not send lead notification email: {}", ex.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @PostMapping("/auto-capture")
    public ResponseEntity<?> autoCaptureFromWhatsApp(@RequestBody WhatsAppAutoCaptureRequest req) {
        log.info("[PublicLead] WhatsApp auto-capture message received from {}", req.getSenderPhone());
        FounderLeadDto lead = leadService.autoCaptureLeadFromWhatsApp(
                req.getSenderPhone(),
                req.getPushName(),
                req.getMessageText()
        );
        return ResponseEntity.ok(ApiResponse.success(lead));
    }
}
