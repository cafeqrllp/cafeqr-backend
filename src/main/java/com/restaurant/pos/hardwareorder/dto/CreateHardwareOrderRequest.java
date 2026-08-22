package com.restaurant.pos.hardwareorder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateHardwareOrderRequest {
    private String planId; // "STARTER", "PRO", "SOFTWARE_ONLY"
    private CustomerDetailsDto customer;
}
