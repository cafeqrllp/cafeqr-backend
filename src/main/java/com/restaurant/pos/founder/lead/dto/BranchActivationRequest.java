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
public class BranchActivationRequest {
    private int daysToAdd; // If provided, adds to current/now
    private LocalDateTime specificExpiryDate; // Or direct target date
    private String subscriptionStatus; // ACTIVE, TRIAL, etc. Default ACTIVE
    private List<String> activeModules; // List of ModuleName strings to activate (e.g. ["KOT", "INVENTORY", "TABLE_QR"])
    private Long paymentAmountPaise; // Payment collected during activation (in paise)
    private String paymentMethod;    // UPI, BANK_TRANSFER, CASH, RAZORPAY, PENDING_INVOICE
    private String paymentNotes;     // Reference ID, notes, etc.
    private boolean recordPayment;   // Whether to record an entry in subscription_payments
}
