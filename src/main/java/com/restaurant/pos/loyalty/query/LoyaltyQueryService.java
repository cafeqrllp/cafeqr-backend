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
        return programRepository.findById(id)
                .map(mapper::toProgramDto)
                .orElseThrow(() -> new BusinessException("Loyalty programme not found: " + id));
    }

    @Transactional(readOnly = true)
    public CustomerLoyaltyDto getCustomerLoyalty(UUID customerId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        CustomerLoyalty account = (orgId != null)
                ? accountRepository.findByCustomerIdAndClientIdAndOrgId(customerId, clientId, orgId)
                        .orElseGet(() -> accountRepository.findByCustomerIdAndClientId(customerId, clientId).orElse(null))
                : accountRepository.findByCustomerIdAndClientId(customerId, clientId).orElse(null);

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
            int customerPoints = (customer != null && customer.getLoyaltyPoints() != null) ? customer.getLoyaltyPoints() : 0;
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

        CustomerLoyaltyDto dto = mapper.toCustomerLoyaltyDto(account, prog);
        if (customer != null && customer.getLoyaltyPoints() != null && customer.getLoyaltyPoints() > dto.getCurrentPoints()) {
            dto.setCurrentPoints(customer.getLoyaltyPoints());
        }
        return dto;
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyTransactionDto> getTransactions(UUID customerId, int page, int size) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));

        Page<LoyaltyTransaction> txns = (orgId != null)
                ? transactionRepository.findByCustomerIdAndClientIdAndOrgIdOrderByCreatedAtDesc(customerId, clientId, orgId, pageable)
                : transactionRepository.findByCustomerIdAndClientIdOrderByCreatedAtDesc(customerId, clientId, pageable);

        if (txns.isEmpty() && orgId != null) {
            txns = transactionRepository.findByCustomerIdAndClientIdOrderByCreatedAtDesc(customerId, clientId, pageable);
        }

        // Batch-fetch program names for all transactions
        Set<UUID> programIds = txns.getContent().stream()
                .map(LoyaltyTransaction::getProgramId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> programNameMap = programIds.isEmpty() ? Map.of()
                : programRepository.findAllById(programIds).stream()
                        .collect(Collectors.toMap(LoyaltyProgram::getId, LoyaltyProgram::getName, (a, b) -> a));

        return txns.map(t -> mapper.toTransactionDto(t, t.getProgramId() != null ? programNameMap.get(t.getProgramId()) : null));
    }
}
