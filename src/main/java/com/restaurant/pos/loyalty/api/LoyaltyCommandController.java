package com.restaurant.pos.loyalty.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.loyalty.command.CreateLoyaltyProgramCommand;
import com.restaurant.pos.loyalty.command.LoyaltyCommandService;
import com.restaurant.pos.loyalty.command.UpdateLoyaltyProgramCommand;
import com.restaurant.pos.loyalty.dto.LoyaltyProgramDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CQRS Command Controller for Loyalty Module.
 * Handles state mutations (CREATE, UPDATE).
 * Lives in feature-based package 'loyalty.api'.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/loyalty/programs")
@RequiredArgsConstructor
@Validated
public class LoyaltyCommandController {

    private final LoyaltyCommandService commandService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoyaltyProgramDto>> createProgram(@Valid @RequestBody CreateLoyaltyProgramCommand cmd) {
        LoyaltyProgramDto created = commandService.createProgram(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoyaltyProgramDto>> updateProgram(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLoyaltyProgramCommand cmd) {
        cmd.setId(id);
        return ResponseEntity.ok(ApiResponse.success(commandService.updateProgram(cmd)));
    }
}
