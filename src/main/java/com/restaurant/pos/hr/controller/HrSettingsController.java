package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.HrSettingsDto;
import com.restaurant.pos.hr.service.HrSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hr/settings")
@RequiredArgsConstructor
public class HrSettingsController {

    private final HrSettingsService hrSettingsService;

    @GetMapping
    public ResponseEntity<HrSettingsDto> getSettings() {
        return ResponseEntity.ok(hrSettingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<HrSettingsDto> updateSettings(@RequestBody HrSettingsDto dto) {
        return ResponseEntity.ok(hrSettingsService.updateSettings(dto));
    }
}
