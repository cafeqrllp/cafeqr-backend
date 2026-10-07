package com.restaurant.pos.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartnerApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> PartnerApiResponse<T> ok(String message, T data) {
        return PartnerApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> PartnerApiResponse<T> error(String message) {
        return PartnerApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
