package com.restaurant.pos.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ClientOrderStatsProjection {
    UUID getClientId();
    Long getOrderCount();
    BigDecimal getTotalGmv();
    LocalDateTime getLastOrderAt();
}
