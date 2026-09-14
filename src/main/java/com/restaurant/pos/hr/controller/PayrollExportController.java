package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.service.PayrollExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/payroll-export")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class PayrollExportController {

    private final PayrollExportService payrollExportService;

    @GetMapping(value = "/ach/{payrollRunId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> downloadAchExport(@PathVariable UUID payrollRunId) {
        String fileContent = payrollExportService.generateAchExport(payrollRunId);
        
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ach_export_" + payrollRunId + ".txt");
        
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.TEXT_PLAIN)
                .body(fileContent);
    }
}
