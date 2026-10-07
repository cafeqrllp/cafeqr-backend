package com.restaurant.pos.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartnerOrderRequestDto {

    private UUID storeId;

    @JsonProperty("partner_order_id")
    private String partnerOrderId;

    @JsonProperty("partner_code")
    private String partnerCode;

    @JsonProperty("fulfillment_type")
    private String fulfillmentType;

    @JsonProperty("payment_mode")
    private String paymentMode;

    private String notes;

    private CustomerDto customer;

    private List<ItemDto> items;

    @JsonProperty("bill_details")
    private BillDetailsDto billDetails;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomerDto {
        private String name;
        private String phone;

        @JsonProperty("delivery_address")
        private String deliveryAddress;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemDto {
        @JsonProperty("external_item_id")
        private String externalItemId;

        @JsonProperty("item_name")
        private String itemName;

        @JsonProperty("variant_name")
        private String variantName;

        private Integer quantity;

        @JsonProperty("unit_price")
        private BigDecimal unitPrice;

        @JsonProperty("total_price")
        private BigDecimal totalPrice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BillDetailsDto {
        private BigDecimal subtotal;

        @JsonProperty("discount_amount")
        private BigDecimal discountAmount;

        @JsonProperty("tax_amount")
        private BigDecimal taxAmount;

        @JsonProperty("grand_total")
        private BigDecimal grandTotal;
    }
}
