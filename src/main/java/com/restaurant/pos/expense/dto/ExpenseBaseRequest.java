package com.restaurant.pos.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared contract for expense request payloads.
 * Both CreateExpenseRequest and UpdateExpenseRequest implement this
 * so the service layer can use a single builder method.
 */
public interface ExpenseBaseRequest {
    UUID getCategoryId();
    Instant getExpenseDate();
    BigDecimal getAmount();
    String getDescription();
    String getPaymentMethod();
    UUID getBranchId();
    String getScope();

    // Optional India GST details
    default String getSupplierName() { return null; }
    default String getSupplierGstin() { return null; }
    default String getBillNo() { return null; }
    default String getHsnSac() { return null; }
    default BigDecimal getGstRate() { return null; }
    default BigDecimal getTaxAmount() { return null; }
}
