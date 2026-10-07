package com.restaurant.pos.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerOrderCancelDto {
    @JsonProperty("partner_order_id")
    private String partnerOrderId;

    @JsonProperty("partner_code")
    private String partnerCode;

    @JsonProperty("store_id")
    private UUID storeId;

    @JsonProperty("cancel_reason")
    private String cancelReason;

    @JsonProperty("cancelled_by")
    private String cancelledBy;
}
