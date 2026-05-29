package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "DTO for updating an existing order")
public class UpdateOrderRequest {

    @Schema(description = "Dining table UUID")
    private UUID tableId;

    @Schema(description = "Dining table number")
    private String tableNumber;

    @Schema(description = "Warehouse UUID for inventory tracking")
    private UUID warehouseId;

    @Schema(description = "Supplier/vendor UUID for purchase orders")
    private UUID vendorId;

    @Schema(description = "Order status (DRAFT, CONFIRMED, COMPLETED, CANCELLED)")
    private String orderStatus;

    @Schema(description = "Payment status (PENDING, PARTIAL, PAID)")
    private String paymentStatus;

    @Schema(description = "Operational description or notes")
    private String description;

    @Schema(description = "Reference ID/No")
    private String reference;

    @Schema(description = "Payment method/mode (CASH, BANK_TRANSFER, UPI, CARD, etc.)")
    private String paymentMethod;

    @Schema(description = "Fulfillment type (DINE_IN, TAKEAWAY, DELIVERY)", example = "DINE_IN")
    private String fulfillmentType;

    @Schema(description = "Dynamic list of customer details linked to this order")
    private com.fasterxml.jackson.databind.JsonNode customerIds;

    @JsonProperty("isCredit")
    @Schema(description = "Whether this order is being completed as a credit sale")
    private Boolean isCredit;

    @Schema(description = "Credit customer UUID for credit sale workflows")
    private UUID creditCustomerId;

    @Valid
    @Schema(description = "List of updated order lines")
    private List<CreateOrderRequest.CreateOrderLineRequest> lines;
}
