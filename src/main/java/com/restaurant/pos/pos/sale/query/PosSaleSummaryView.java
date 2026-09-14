package com.restaurant.pos.pos.sale.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight Spring Data interface-based projection for POS Sales History.
 * <p>
 * Each getter maps to a column alias in the native SQL query defined in
 * {@link PosSaleProjectionRepository}. Using an interface projection avoids
 * loading the full JPA {@code Order} entity graph and completely eliminates
 * the N+1 hydration cascade (order-lines, customers, payments, formulas).
 * </p>
 */
public interface PosSaleSummaryView {
    UUID getOrderId();
    String getOrderNo();
    Instant getOrderDate();
    String getOrderStatus();
    String getPaymentStatus();
    String getFulfillmentType();
    UUID getTableId();
    String getTableNumber();
    UUID getCustomerId();
    String getCustomerName();
    String getCustomerPhone();
    Boolean getIsCredit();
    UUID getCreditCustomerId();
    BigDecimal getTotalAmount();
    BigDecimal getTotalTaxAmount();
    BigDecimal getTotalDiscountAmount();
    BigDecimal getGrandTotal();
    BigDecimal getGrossAmount();
    String getPaymentMethod();
    String getInvoiceNo();
    Integer getDailyBillNo();
    String getPaymentNo();
    Integer getItemCount();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
    String getCreatedBy();
}
