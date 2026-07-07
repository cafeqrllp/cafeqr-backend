package com.restaurant.pos.order.dto;

import com.restaurant.pos.order.domain.TaxType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatedLine {
    private UUID lineId;
    private UUID clientLineId;

    private UUID productId;
    private UUID variantId;
    private String productName;
    private String categoryName;
    private Boolean isPackagedGood;

    private BigDecimal quantity;
    private BigDecimal unitPrice;       // face/MRP input price
    private BigDecimal unitPriceExTax;  // ex-tax unit price

    // ── Pipeline stage 1: gross (before any discount) ──────────────────────
    private BigDecimal grossBaseAmount; // unitPriceExTax × qty
    private BigDecimal grossFaceAmount; // customer-visible gross (inclusive-equivalent)
    private BigDecimal grossLineAmount; // alias = grossFaceAmount (backward compat)

    // ── Pipeline stage 2: line discount ────────────────────────────────────
    private String lineDiscountInputType;
    private BigDecimal lineDiscountInputValue;
    private BigDecimal lineDiscountBaseAmount; // ex-tax line discount
    private BigDecimal lineDiscountFaceAmount; // customer-visible line discount (face)

    // ── Pipeline stage 3: after line discount (eligible for order discount) ─
    private BigDecimal baseAfterLineDiscount;  // grossBaseAmount − lineDiscountBaseAmount
    private BigDecimal faceAfterLineDiscount;  // grossFaceAmount − lineDiscountFaceAmount

    // ── Pipeline stage 4: order-level discount allocation ──────────────────
    private BigDecimal allocatedOrderDiscountBase; // ex-tax share of order discount
    private BigDecimal allocatedOrderDiscountFace; // customer-visible share (face)

    // ── Pipeline stage 5: final settled amounts ────────────────────────────
    private BigDecimal taxableAmount; // finalBase  = baseAfterLineDiscount − allocatedBase
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;     // final customer-payable line amount after all discounts and tax

    private TaxType taxType;
    private String taxCode;
    private String taxName;
}
