package com.restaurant.pos.invoice.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Invoice>> getInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getInvoice(id)));
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Invoice>> getInvoiceByOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getInvoiceByOrder(orderId)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Invoice>> createInvoice(@RequestBody Invoice invoice) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.createInvoice(invoice)));
    }
}
