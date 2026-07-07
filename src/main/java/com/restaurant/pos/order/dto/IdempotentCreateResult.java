package com.restaurant.pos.order.dto;

import com.restaurant.pos.order.domain.Order;

public record IdempotentCreateResult(
        Order order,
        boolean created
) {}
