package com.restaurant.pos.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientSummaryDto {

    // Core client info
    private UUID id;
    private String name;
    private String legalName;
    private String ownerName;
    private String email;
    private String phone;
    private String address;
    private String country;
    private String currency;
    private String timezone;
    private String posType;
    private String website;
    private String panNumber;
    private String gstNumber;
    private String fssaiNumber;
    private String instagramUrl;
    private String facebookUrl;
    private String whatsappNumber;
    private String bankName;
    private String accountNumber;
    private String ifscCode;
    private String isactive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Subscription info
    private String subscriptionStatus;
    private LocalDateTime subscriptionExpiryDate;
    private int daysLeft;

    // Module info
    private List<ModuleInfo> subscriptionModules;

    // Payment history
    private List<PaymentInfo> paymentHistory;

    // Computed
    private long totalPaidPaise;
    private int paymentCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleInfo {
        private String moduleName;
        private String status;
        private LocalDateTime expiryDate;
        private boolean autoRenew;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInfo {
        private UUID id;
        private String paymentId;
        private String orderId;
        private long amount;
        private String currency;
        private LocalDateTime createdAt;
    }
}
