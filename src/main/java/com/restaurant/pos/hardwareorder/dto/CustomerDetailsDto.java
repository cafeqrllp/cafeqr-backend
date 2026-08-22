package com.restaurant.pos.hardwareorder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDetailsDto {
    private String name;
    private String phone;
    private String email;
    private String addressLine1;
    private String area;
    private String city;
    private String state;
    private String pincode;
}
