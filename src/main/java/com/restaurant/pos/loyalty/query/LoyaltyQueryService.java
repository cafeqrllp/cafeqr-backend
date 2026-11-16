package com.restaurant.pos.loyalty.query;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.domain.*;
import com.restaurant.pos.loyalty.dto.*;
import com.restaurant.pos.loyalty.mapper.LoyaltyDtoMapper;
import com.restaurant.pos.loyalty.repository.CustomerLoyaltyRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyProgramRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Query Service for Loyalty Module (CQRS pattern matching purchase.query).
 * Handles read operations (getPrograms, getProgram, getCustomerLoyalty, getTransactions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyQueryService {

    private final LoyaltyProgramRepository programRepository;
    private final CustomerLoyaltyRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final com.restaurant.pos.purchasing.repository.CustomerRepository customerRepository;
    private final com.restaurant.pos.client.repository.OrganizationRepository organizationRepository;
    private final LoyaltyDtoMapper mapper;

    @Transactional(readOnly = true)
    public List<LoyaltyProgramDto> getPrograms() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        // Return both branch-specific and client-wide programs visible to this branch
        List<LoyaltyProgram> programs = (orgId != null)
                ? programRepository.findAllVisibleForOrg(clientId, orgId)
                : programRepository.findByClientIdAndOrgIdIsNullOrderByPriorityDescNameAsc(clientId);

        Set<UUID> orgIds = programs.stream()
                .map(LoyaltyProgram::getOrgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, String> orgNameMap = orgIds.isEmpty() ? Map.of()
                : organizationRepository.findAllById(orgIds).stream()
                        .collect(Collectors.toMap(
                                com.restaurant.pos.client.domain.Organization::getId,
                                com.restaurant.pos.client.domain.Organization::getName,
                                (a, b) -> a));

        return programs.stream()
                .map(p -> mapper.toProgramDto(p, p.getOrgId() != null ? orgNameMap.get(p.getOrgId()) : null))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LoyaltyProgramDto getProgram(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();
        // Only programmes of this client that are visible to this branch (own branch or client-wide)
        return programRepository.findById(id)
                .filter(p -> Objects.equals(p.getClientId(), clientId))
                .filter(p -> orgId == null || p.getOrgId() == null || Objects.equals(p.getOrgId(), orgId))
                .map(mapper::toProgramDto)
                .orElseThrow(() -> new BusinessException("Loyalty programme not found."));
    }

    @Transactional
    public CustomerLoyaltyDto getCustomerLoyalty(UUID customerId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        // One account per customer per client (points are shared across branches) — the same
        // account LoyaltyCommandService earns into and redeems from.
        List<CustomerLoyalty> accounts = accountRepository.findByCustomerIdAndClientIdOrderByCreatedAtAsc(customerId, clientId);
        CustomerLoyalty account = (accounts == null || accounts.isEmpty()) ? null : accounts.get(0);

        com.restaurant.pos.purchasing.domain.Customer customer = customerRepository.findById(customerId).orElse(null);

        // Strict active + default resolution: branch first, then client-wide
        LoyaltyProgram activeDefault = null;
        if (orgId != null) {
            activeDefault = programRepository.findByClientIdAndOrgIdAndIsDefaultTrueAndIsActiveTrue(clientId, orgId).orElse(null);
        }
        if (activeDefault == null) {
            activeDefault = programRepository.findByClientIdAndOrgIdIsNullAndIsDefaultTrueAndIsActiveTrue(clientId).orElse(null);
        }

        if (account == null) {
            // No loyalty account yet = nothing redeemable. Do not show the legacy customers.loyalty_points
            // value here: the POS would offer points that redemption then rejects.
            int customerPoints = 0;
            return CustomerLoyaltyDto.builder()
                    .customerId(customerId)
                    .customerName(customer != null ? customer.getName() : null)
                    .customerPhone(customer != null ? customer.getPhone() : null)
                    .programId(activeDefault != null ? activeDefault.getId() : null)
                    .programName(activeDefault != null ? activeDefault.getName() : null)
                    .currentPoints(customerPoints)
                    .lifetimeEarned(customerPoints)
                    .lifetimeRedeemed(0)
                    .build();
        }

        // Only active default program is applied
        LoyaltyProgram prog = activeDefault;
        if (prog != null && (account.getProgramId() == null || !account.getProgramId().equals(prog.getId()))) {
            account.setProgramId(prog.getId());
            accountRepository.save(account);
        } else if (prog == null && account.getProgramId() != null) {
            account.setProgramId(null);
            accountRepository.save(account);
        }

        reconcileAccountFromLedger(account, customerId, clientId);

        CustomerLoyaltyDto dto = mapper.toCustomerLoyaltyDto(account, prog);
        if (customer != null && !Objects.equals(customer.getLoyaltyPoints(), account.getCurrentPoints())) {
            customer.setLoyaltyPoints(account.getCurrentPoints());
            customerRepository.save(customer);
        }
        return dto;
    }

    private void reconcileAccountFromLedger(CustomerLoyalty account, UUID customerId, UUID clientId) {
        if (account == null || customerId == null) return;
        // Reconcile against this account's own ledger. Entries carry the org of the branch that
        // posted them, so filtering by the account's org would drop other branches' activity.
        List<LoyaltyTransaction> txns = account.getId() != null
                ? transactionRepository.findByCustomerLoyaltyIdOrderByCreatedAtAsc(account.getId())
                : List.of();

        if (txns.isEmpty()) return;

        int calculatedEarned = 0;
        int calculatedRedeemed = 0;

        for (LoyaltyTransaction t : txns) {
            if (t.getTransactionType() == LoyaltyTransactionType.ADJUSTMENT) {
                // Manual correction: positive adds to earned, negative counts as used
                if (t.getPoints() >= 0) calculatedEarned += t.getPoints();
                else calculatedRedeemed += Math.abs(t.getPoints());
            } else if (t.getTransactionType() == LoyaltyTransactionType.EXPIRE) {
                calculatedRedeemed += Math.abs(t.getPoints());
            } else if (t.getTransactionType() == LoyaltyTransactionType.EARN) {
                calculatedEarned += t.getPoints();
            } else if (t.getTransactionType() == LoyaltyTransactionType.REDEEM) {
                calculatedRedeemed += Math.abs(t.getPoints());
            } else if (t.getTransactionType() == LoyaltyTransactionType.REVERSAL) {
                if (t.getPoints() < 0) {
                    // Reversal of EARN: deduct from lifetime earned
                    calculatedEarned = Math.max(0, calculatedEarned - Math.abs(t.getPoints()));
                } else {
                    // Reversal of REDEEM: deduct from lifetime redeemed
                    calculatedRedeemed = Math.max(0, calculatedRedeemed - t.getPoints());
                }
            }
        }

        int calculatedBalance = Math.max(0, calculatedEarned - calculatedRedeemed);

        boolean changed = false;
        if (account.getCurrentPoints() != calculatedBalance) {
            account.setCurrentPoints(calculatedBalance);
            changed = true;
        }
        if (account.getLifetimeEarned() != calculatedEarned) {
            account.setLifetimeEarned(calculatedEarned);
            changed = true;
        }
        if (account.getLifetimeRedeemed() != calculatedRedeemed) {
            account.setLifetimeRedeemed(calculatedRedeemed);
            changed = true;
        }

        if (changed) {
            accountRepository.save(account);
            log.info("Reconciled CustomerLoyalty for customerId={}: balance={}, earned={}, redeemed={}",
                    customerId, calculatedBalance, calculatedEarned, calculatedRedeemed);
        }
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyTransactionDto> getTransactions(UUID customerId, int page, int size) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));

        // Points are shared across branches, so show the full history; each entry carries its branch.
        Page<LoyaltyTransaction> txns =
                transactionRepository.findByCustomerIdAndClientIdOrderByCreatedAtDesc(customerId, clientId, pageable);

        // Batch-fetch program names for all transactions
        Set<UUID> programIds = txns.getContent().stream()
                .map(LoyaltyTransaction::getProgramId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> programNameMap = programIds.isEmpty() ? Map.of()
                : programRepository.findAllById(programIds).stream()
                        .collect(Collectors.toMap(LoyaltyProgram::getId, LoyaltyProgram::getName, (a, b) -> a));

        Set<UUID> txnOrgIds = txns.getContent().stream()
                .map(LoyaltyTransaction::getOrgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> branchNameMap = txnOrgIds.isEmpty() ? Map.of()
                : organizationRepository.findAllById(txnOrgIds).stream()
                        .collect(Collectors.toMap(
                                com.restaurant.pos.client.domain.Organization::getId,
                                com.restaurant.pos.client.domain.Organization::getName,
                                (a, b) -> a));

        return txns.map(t -> mapper.toTransactionDto(t,
                t.getProgramId() != null ? programNameMap.get(t.getProgramId()) : null,
                t.getOrgId() != null ? branchNameMap.get(t.getOrgId()) : null));
    }
}
