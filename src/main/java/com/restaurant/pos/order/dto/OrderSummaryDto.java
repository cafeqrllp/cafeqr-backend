package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.order.domain.OrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryDto {
    private UUID id;
    private String orderNo;
    private OrderType orderType;
    private String orderStatus;
    private String paymentStatus;
    private String fulfillmentType;
    private UUID tableId;
    private String tableNumber;
    private UUID customerId;
    private String customerName;
    private String customerPhone;
    @JsonProperty("isCredit")
    private Boolean isCredit;
    private UUID creditCustomerId;
    @Builder.Default
    private List<OrderCustomerDto> customers = new ArrayList<>();
    private BigDecimal totalAmount;
    private BigDecimal totalTaxAmount;
    private BigDecimal totalDiscountAmount;
    private BigDecimal grandTotal;
    private Instant orderDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String invoiceNo;
    private String paymentNo;
    private String paymentMethod;
    private String description;
    @Builder.Default
    private List<OrderLineSummaryDto> lines = new ArrayList<>();
    private UUID warehouseId;
    private UUID vendorId;
}
