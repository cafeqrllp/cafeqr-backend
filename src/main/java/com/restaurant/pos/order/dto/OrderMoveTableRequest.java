package com.restaurant.pos.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Request payload for moving an order to a different dining table")
public class OrderMoveTableRequest {

    @Schema(description = "Unique UUID of the target dining table", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID tableId;

    @Schema(description = "User-friendly table number/identifier", example = "Table 12")
    private String tableNumber;
}
