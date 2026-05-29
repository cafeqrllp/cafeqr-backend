package com.restaurant.pos.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request payload for cancelling an order")
public class OrderCancelRequest {

    @Schema(description = "Description of the cancel reason", example = "Customer walked away / order printed incorrectly")
    private String reason;
}
