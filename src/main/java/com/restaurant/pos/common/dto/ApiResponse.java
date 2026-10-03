package com.restaurant.pos.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private String timestamp;
    private String errorReference;
    private java.util.List<String> warnings;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message("Success")
                .data(data)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    public static <T> ApiResponse<T> successWithWarnings(T data, java.util.List<String> warnings) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(warnings != null && !warnings.isEmpty() ? "Warning: " + String.join("; ", warnings) : "Success")
                .data(data)
                .warnings(warnings)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    public static <T> ApiResponse<T> error(String message, String errorReference) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errorReference(errorReference)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }
}
