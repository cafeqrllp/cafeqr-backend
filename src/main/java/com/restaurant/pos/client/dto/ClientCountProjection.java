package com.restaurant.pos.client.dto;

import java.util.UUID;

public interface ClientCountProjection {
    UUID getClientId();
    Long getCount();
}
