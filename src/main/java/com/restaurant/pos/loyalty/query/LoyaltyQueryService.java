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

import java.util.List;
import java.util.UUID;
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
    private final LoyaltyDtoMapper mapper;

    @Transactional(readOnly = true)
    public List<LoyaltyProgramDto> getPrograms() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        List<LoyaltyProgram> programs = (orgId != null)
                ? programRepository.findByClientIdAndOrgIdOrderByPriorityDescNameAsc(clientId, orgId)
                : programRepository.findByClientIdAndOrgIdIsNullOrderByPriorityDescNameAsc(clientId);

        return programs.stream().map(mapper::toProgramDto).collect(Collectors.toList());
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

        List<LoyaltyProgram> programs = (orgId != null)
                ? programRepository.findByClientIdAndOrgIdOrderByPriorityDescNameAsc(clientId, orgId)
                : programRepository.findByClientIdAndOrgIdIsNullOrderByPriorityDescNameAsc(clientId);

        LoyaltyProgram defaultProg = programs.stream().filter(LoyaltyProgram::isDefault).findFirst()
                .orElseGet(() -> programs.isEmpty() ? null : programs.get(0));

        if (account == null) {
            int customerPoints = (customer != null && customer.getLoyaltyPoints() != null) ? customer.getLoyaltyPoints() : 0;
            return CustomerLoyaltyDto.builder()
                    .customerId(customerId)
                    .customerName(customer != null ? customer.getName() : null)
                    .customerPhone(customer != null ? customer.getPhone() : null)
                    .programId(defaultProg != null ? defaultProg.getId() : null)
                    .programName(defaultProg != null ? defaultProg.getName() : null)
                    .currentPoints(customerPoints)
                    .lifetimeEarned(customerPoints)
                    .lifetimeRedeemed(0)
                    .build();
        }

        LoyaltyProgram prog = account.getProgramId() != null
                ? programRepository.findById(account.getProgramId()).orElse(defaultProg)
                : defaultProg;

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

        return txns.map(mapper::toTransactionDto);
    }
}
