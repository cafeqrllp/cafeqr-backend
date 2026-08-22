package com.restaurant.pos.hardwareorder.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.hardwareorder.dto.CreateHardwareOrderRequest;
import com.restaurant.pos.hardwareorder.dto.VerifyHardwareOrderRequest;
import com.restaurant.pos.hardwareorder.service.HardwareOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(originPatterns = "*", allowedHeaders = "*")
@RestController
@RequestMapping("/api/v1/public/hardware-order")
@RequiredArgsConstructor
public class HardwareOrderController {

    private final HardwareOrderService hardwareOrderService;

    @PostMapping("/create-payment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPayment(
            @RequestBody CreateHardwareOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success(hardwareOrderService.createPayment(request)));
    }

    @PostMapping("/verify-payment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPayment(
            @RequestBody VerifyHardwareOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success(hardwareOrderService.verifyPayment(request)));
    }
}
