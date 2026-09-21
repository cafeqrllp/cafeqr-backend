package com.restaurant.pos.accounting.controller;


import com.restaurant.pos.accounting.domain.*;
import com.restaurant.pos.accounting.dto.AccountingBackfillRequest;
import com.restaurant.pos.accounting.dto.AccountingBackfillResponse;
import com.restaurant.pos.accounting.dto.AccountingAccountPeriodDto;
import com.restaurant.pos.accounting.dto.AccountingMappingsDto;
import com.restaurant.pos.accounting.dto.AccountingPostingErrorDto;
import com.restaurant.pos.accounting.dto.AccountingReconciliationDto;
import com.restaurant.pos.accounting.dto.AccountingSummaryDto;
import com.restaurant.pos.accounting.dto.TrialBalanceRowDto;
import com.restaurant.pos.accounting.service.AccountingDefaultsService;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.accounting.service.AccountingService;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounting")
@RequiredArgsConstructor
public class AccountingController {
    private final AccountingService accountingService;
    private final AccountingDefaultsService defaultsService;
    private final AccountingPostingService postingService;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<AccountingAccountPeriodDto>>> getAccounts(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId
    ) {
        UUID effectiveOrgId = orgId != null ? orgId : com.restaurant.pos.common.tenant.TenantContext.getCurrentOrg();
        if (effectiveOrgId != null) {
            defaultsService.ensureDefaultAccounts(effectiveOrgId);
        }
        return ResponseEntity.ok(ApiResponse.success(accountingService.getPeriodAccounts(
                parseAccountingDateTime(from),
                parseAccountingDateTime(to),
                includeInactive,
                orgId,
                terminalId
        )));
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingAccount>> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.getAccount(id)));
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingAccount>> createAccount(@RequestBody AccountingAccount account) {
        return ResponseEntity.ok(ApiResponse.success("Account created", accountingService.createAccount(account)));
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingAccount>> updateAccount(
            @PathVariable UUID id,
            @RequestBody AccountingAccount account
    ) {
        return ResponseEntity.ok(ApiResponse.success("Account updated", accountingService.updateAccount(id, account)));
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Void>> deactivateAccount(@PathVariable UUID id) {
        accountingService.deactivateAccount(id);
        return ResponseEntity.ok(ApiResponse.success("Account deactivated", null));
    }

    @GetMapping("/journals")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<JournalEntry>>> getJournalEntries(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "entryDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.getJournalEntries(parseAccountingDateTime(from), parseAccountingDateTime(to), sortBy, sortDir)));
    }

    @PostMapping("/journals")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<JournalEntry>> createJournalEntry(@RequestBody JournalEntry entry) {
        return ResponseEntity.ok(ApiResponse.success("Journal posted", accountingService.createJournalEntry(entry)));
    }

    @GetMapping("/party-ledger")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<PartyLedgerEntry>>> getPartyLedger(
            @RequestParam PartyType partyType,
            @RequestParam UUID partyId
    ) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.getPartyLedger(partyType, partyId)));
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<TrialBalanceRowDto>>> getTrialBalance(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.getTrialBalance(parseAccountingDateTime(from), parseAccountingDateTime(to))));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingSummaryDto>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId
    ) {
        UUID effectiveOrgId = orgId != null ? orgId : com.restaurant.pos.common.tenant.TenantContext.getCurrentOrg();
        if (effectiveOrgId != null) {
            defaultsService.ensureDefaultAccounts(effectiveOrgId);
        }
        return ResponseEntity.ok(ApiResponse.success(accountingService.getSummary(
                parseAccountingDateTime(from),
                parseAccountingDateTime(to),
                orgId,
                terminalId
        )));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingReconciliationDto>> getReconciliation(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId
    ) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.getReconciliation(parseAccountingDateTime(from), parseAccountingDateTime(to), orgId, terminalId)));
    }

    @PostMapping("/payment-allocations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<PaymentAllocation>> allocatePayment(@RequestBody PaymentAllocation allocation) {
        return ResponseEntity.ok(ApiResponse.success("Payment allocated", accountingService.allocatePayment(allocation)));
    }

    @PostMapping("/defaults/ensure")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<AccountingAccount>>> ensureDefaults() {
        return ResponseEntity.ok(ApiResponse.success("Accounting defaults ensured", defaultsService.ensureDefaultAccounts()));
    }

    @GetMapping("/mappings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingMappingsDto>> getMappings() {
        defaultsService.ensureDefaultAccounts();
        return ResponseEntity.ok(ApiResponse.success(defaultsService.getMappings()));
    }

    @PutMapping("/mappings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingMappingsDto>> updateMappings(@RequestBody AccountingMappingsDto mappings) {
        return ResponseEntity.ok(ApiResponse.success("Accounting mappings updated", defaultsService.updateMappings(mappings)));
    }

    @GetMapping("/posting-errors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<AccountingPostingErrorDto>>> getPostingErrors() {
        return ResponseEntity.ok(ApiResponse.success(postingService.getPostingErrors()));
    }

    @PostMapping("/posting-errors/{id}/retry")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingPostingJob>> retryPosting(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Accounting posting retry queued", postingService.retryPosting(id)));
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingBackfillResponse>> backfill(@RequestBody AccountingBackfillRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting backfill completed", postingService.backfill(request)));
    }

    @PostMapping("/resync-all")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<AccountingBackfillResponse>> resyncAll() {
        return ResponseEntity.ok(ApiResponse.success("Auto-posted accounting data safely rebuilt", postingService.resyncAll()));
    }

    private LocalDateTime parseAccountingDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        ZoneId zoneId = timezoneResolver.resolveTimezone(com.restaurant.pos.common.tenant.TenantContext.getCurrentTenant(), com.restaurant.pos.common.tenant.TenantContext.getCurrentOrg());
        try {
            return Instant.parse(trimmed).atZone(zoneId).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed).atZoneSameInstant(zoneId).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid accounting date-time: " + value);
        }
    }
}
