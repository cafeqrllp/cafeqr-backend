package com.restaurant.pos.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String role;
}
