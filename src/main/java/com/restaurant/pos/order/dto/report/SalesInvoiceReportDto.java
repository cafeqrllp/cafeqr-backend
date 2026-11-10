package com.restaurant.pos.order.dto.report;

import com.restaurant.pos.order.dto.OrderCustomerDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesInvoiceReportDto {
    private String id;
    private UUID orderId;
    private UUID invoiceId;
    private UUID branchId;
    private String branchName;
    private String branchCode;
    private String orderNo;
    private String invoiceNo;
    private String paymentNo;
    private String orderStatus;
    private String paymentStatus;
    private String invoiceStatus;
    private String invoiceDocStatus;
    private String fulfillmentType;
    private String tableNumber;
    private String customerName;
    private String customerPhone;
    private List<OrderCustomerDto> customers;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private BigDecimal totalTaxAmount;
    private BigDecimal totalDiscountAmount;
    private BigDecimal grandTotal;
    private BigDecimal amountDue;
    private LocalDateTime transactionDate;
    private LocalDateTime orderDate;
    private LocalDateTime invoiceDate;
    private LocalDateTime createdAt;
    private boolean voidable;
    private String voidReason;
    private List<LineDto> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDto {
        private UUID productId;
        private String productName;
        private String categoryName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal taxRate;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private BigDecimal lineTotal;
        @com.fasterxml.jackson.annotation.JsonAlias({"tax_code", "hsnCode", "hsn_code"})
        private String taxCode;
    }
}
