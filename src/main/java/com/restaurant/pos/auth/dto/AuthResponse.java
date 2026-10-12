package com.restaurant.pos.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private java.util.UUID clientId;
    private String clientName;
    private java.util.UUID orgId;
    private String orgName;
    private java.util.UUID terminalId;
    private String terminalName;
    private java.util.UUID userId;
    private String currency;
    private String country;
    private String subscriptionStatus;
    private java.time.LocalDateTime subscriptionExpiryDate;
    private Boolean canCancelOrder;
    private Boolean canDeleteOrderItem;
    private Boolean canDecrementOrderItem;
    private String timezone;
    private String termsAcceptedVersion;
    private java.time.LocalDateTime termsAcceptedAt;
}

