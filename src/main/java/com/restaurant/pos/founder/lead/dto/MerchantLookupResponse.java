package com.restaurant.pos.founder.lead.dto;

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
public class MerchantLookupResponse {
    private boolean matched;
    private String matchedType; // "BRANCH", "CLIENT", "USER", "NONE"
    private UUID orgId;
    private UUID clientId;
    private String businessName;
    private String branchName;
    private String branchCode;
    private String contactPerson;
    private String phone;
    private String whatsappNumber;
    private String email;
    private String city;
    private String subscriptionStatus;
    private LocalDateTime subscriptionExpiryDate;
    private boolean isSubscriptionActive;
    private List<String> activeModules;
}
