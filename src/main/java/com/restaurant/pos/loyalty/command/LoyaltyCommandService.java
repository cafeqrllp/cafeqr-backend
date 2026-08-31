package com.restaurant.pos.loyalty.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.domain.*;
import com.restaurant.pos.loyalty.dto.LoyaltyProgramDto;
import com.restaurant.pos.loyalty.mapper.LoyaltyDtoMapper;
import com.restaurant.pos.loyalty.repository.CustomerLoyaltyRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyProgramRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Command Service for Loyalty Module (CQRS pattern matching purchase.command).
 * Handles all state mutations (CREATE, UPDATE, EARN, REDEEM, REVERSE).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCommandService {

    private final LoyaltyProgramRepository programRepository;
    private final CustomerLoyaltyRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final SystemConfigurationService configService;
    private final LoyaltyDtoMapper mapper;

    @Transactional
    public LoyaltyProgramDto createProgram(CreateLoyaltyProgramCommand cmd) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (cmd.isDefault()) {
            clearDefault(clientId, orgId);
        }

        LoyaltyProgram program = LoyaltyProgram.builder()
                .name(cmd.getName())
                .description(cmd.getDescription())
                .isActive(cmd.isActive())
                .isDefault(cmd.isDefault())
                .priority(cmd.getPriority())
                .build();
        program.setClientId(clientId);
        program.setOrgId(orgId);
        program = programRepository.save(program);

        program.getEarnRules().add(LoyaltyEarnRule.builder()
                .program(program)
                .spendAmount(cmd.getSpendAmount())
                .earnPoints(cmd.getEarnPoints())
                .build());

        program.getRedemptionRules().add(LoyaltyRedemptionRule.builder()
                .program(program)
                .pointsRequired(cmd.getPointsRequired())
                .discountAmount(cmd.getDiscountAmount())
                .minPoints(cmd.getMinPoints())
                .maxPointsPerOrder(cmd.getMaxPointsPerOrder())
                .allowPartial(cmd.isAllowPartial())
                .build());

        return mapper.toProgramDto(programRepository.save(program));
    }

    @Transactional
    public LoyaltyProgramDto updateProgram(UpdateLoyaltyProgramCommand cmd) {
        LoyaltyProgram program = programRepository.findById(cmd.getId())
                .orElseThrow(() -> new BusinessException("Loyalty programme not found: " + cmd.getId()));

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (cmd.isDefault() && !program.isDefault()) {
            clearDefault(clientId, orgId);
        }

        program.setName(cmd.getName());
        program.setDescription(cmd.getDescription());
        program.setActive(cmd.isActive());
        program.setDefault(cmd.isDefault());
        program.setPriority(cmd.getPriority());

        program.getEarnRules().clear();
        program.getEarnRules().add(LoyaltyEarnRule.builder()
                .program(program)
                .spendAmount(cmd.getSpendAmount())
                .earnPoints(cmd.getEarnPoints())
                .build());

        program.getRedemptionRules().clear();
        program.getRedemptionRules().add(LoyaltyRedemptionRule.builder()
                .program(program)
                .pointsRequired(cmd.getPointsRequired())
                .discountAmount(cmd.getDiscountAmount())
                .minPoints(cmd.getMinPoints())
                .maxPointsPerOrder(cmd.getMaxPointsPerOrder())
                .allowPartial(cmd.isAllowPartial())
                .build());

        return mapper.toProgramDto(programRepository.save(program));
    }

    @Transactional
    public Optional<LoyaltyTransaction> earnPoints(UUID customerId, UUID orderId, BigDecimal eligibleAmount) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (customerId == null || eligibleAmount == null || eligibleAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        if (!isLoyaltyEnabled(clientId, orgId)) {
            log.info("Loyalty EARN skipped: loyalty setting is disabled for client={} org={}", clientId, orgId);
            return Optional.empty();
        }

        Optional<LoyaltyProgram> programOpt = resolveProgram(customerId, clientId, orgId);
        if (programOpt.isEmpty() || programOpt.get().getEarnRules().isEmpty()) {
            return Optional.empty();
        }

        LoyaltyProgram  program  = programOpt.get();
        LoyaltyEarnRule earnRule = program.getEarnRules().get(0);

        int points = eligibleAmount
                .divideToIntegralValue(earnRule.getSpendAmount())
                .multiply(BigDecimal.valueOf(earnRule.getEarnPoints()))
                .intValue();

        if (points <= 0) return Optional.empty();

        if (orderId != null) {
            List<LoyaltyTransaction> existingTxns = transactionRepository.findByOrderIdAndClientId(orderId, clientId);
            if (existingTxns.stream().anyMatch(t -> t.getTransactionType() == LoyaltyTransactionType.EARN)) {
                log.info("Loyalty EARN already processed for orderId={}", orderId);
                return Optional.empty();
            }
        }

        CustomerLoyalty account = getOrCreateAccount(customerId, clientId, orgId, program.getId());
        account.creditPoints(points);
        accountRepository.save(account);

        LoyaltyTransaction txn = LoyaltyTransaction.builder()
                .customerLoyaltyId(account.getId())
                .customerId(customerId)
                .clientId(clientId)
                .orgId(orgId)
                .programId(program.getId())
                .orderId(orderId)
                .transactionType(LoyaltyTransactionType.EARN)
                .points(points)
                .balanceAfter(account.getCurrentPoints())
                .remarks("Earned on order completion")
                .build();

        LoyaltyTransaction saved = transactionRepository.save(txn);
        log.info("Loyalty EARN: customer={} order={} points=+{} balance={}", customerId, orderId, points, account.getCurrentPoints());
        return Optional.of(saved);
    }

    @Transactional
    public BigDecimal redeemPoints(UUID customerId, UUID orderId, int pointsToUse) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (!isLoyaltyEnabled(clientId, orgId)) {
            throw new BusinessException("Loyalty is not enabled for this organisation.");
        }

        if (orderId != null) {
            List<LoyaltyTransaction> existingTxns = transactionRepository.findByOrderIdAndClientId(orderId, clientId);
            if (existingTxns.stream().anyMatch(t -> t.getTransactionType() == LoyaltyTransactionType.REDEEM)) {
                log.info("Loyalty REDEEM already processed for orderId={}", orderId);
                return BigDecimal.ZERO;
            }
        }

        CustomerLoyalty account = (orgId != null)
                ? accountRepository.findByCustomerIdAndClientIdAndOrgIdWithLock(customerId, clientId, orgId)
                        .orElseThrow(() -> new BusinessException("No loyalty account found for customer."))
                : accountRepository.findByCustomerIdAndClientIdWithLock(customerId, clientId)
                        .orElseThrow(() -> new BusinessException("No loyalty account found for customer."));

        LoyaltyProgram program = account.getProgramId() != null
                ? programRepository.findById(account.getProgramId())
                        .orElseThrow(() -> new BusinessException("Programme not found."))
                : null;

        if (program == null || program.getRedemptionRules().isEmpty()) {
            throw new BusinessException("No redemption rule configured for this programme.");
        }

        LoyaltyRedemptionRule rule = program.getRedemptionRules().get(0);

        if (account.getCurrentPoints() < rule.getMinPoints()) {
            throw new BusinessException("Minimum " + rule.getMinPoints() + " points required. Available: " + account.getCurrentPoints());
        }
        if (pointsToUse > account.getCurrentPoints()) {
            throw new BusinessException("Insufficient points. Available: " + account.getCurrentPoints());
        }
        if (rule.getMaxPointsPerOrder() != null && pointsToUse > rule.getMaxPointsPerOrder()) {
            throw new BusinessException("Maximum " + rule.getMaxPointsPerOrder() + " points per order.");
        }

        int slabs = pointsToUse / rule.getPointsRequired();
        BigDecimal discount = rule.getDiscountAmount()
                .multiply(BigDecimal.valueOf(slabs))
                .setScale(2, RoundingMode.HALF_UP);

        account.debitPoints(pointsToUse);
        accountRepository.save(account);

        transactionRepository.save(LoyaltyTransaction.builder()
                .customerLoyaltyId(account.getId())
                .customerId(customerId)
                .clientId(clientId)
                .orgId(orgId)
                .programId(account.getProgramId())
                .orderId(orderId)
                .transactionType(LoyaltyTransactionType.REDEEM)
                .points(-pointsToUse)
                .balanceAfter(account.getCurrentPoints())
                .remarks("Redeemed for ₹" + discount + " discount")
                .build());

        log.info("Loyalty REDEEM: customer={} order={} points=-{} discount={}", customerId, orderId, pointsToUse, discount);
        return discount;
    }

    @Transactional
    public void reverseOrderTransactions(UUID orderId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (!isLoyaltyEnabled(clientId, orgId)) {
            return;
        }

        List<LoyaltyTransaction> originals = transactionRepository.findByOrderIdAndClientId(orderId, clientId);
        if (originals.isEmpty()) return;

        for (LoyaltyTransaction original : originals) {
            if (original.getTransactionType() == LoyaltyTransactionType.REVERSAL) continue;

            CustomerLoyalty account = accountRepository.findById(original.getCustomerLoyaltyId())
                    .orElseThrow(() -> new BusinessException("Loyalty account not found for reversal."));

            int reversalPoints = -original.getPoints();
            if (reversalPoints > 0) {
                account.creditPoints(reversalPoints);
            } else {
                account.debitPoints(Math.abs(reversalPoints));
            }
            accountRepository.save(account);

            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerLoyaltyId(account.getId())
                    .customerId(original.getCustomerId())
                    .clientId(clientId)
                    .orgId(original.getOrgId())
                    .programId(original.getProgramId())
                    .orderId(orderId)
                    .transactionType(LoyaltyTransactionType.REVERSAL)
                    .points(reversalPoints)
                    .balanceAfter(account.getCurrentPoints())
                    .referenceTransactionId(original.getId())
                    .remarks("Reversal of " + original.getTransactionType() + " on order cancellation/refund")
                    .build());

            log.info("Loyalty REVERSAL: original={} order={} points={}", original.getId(), orderId, reversalPoints);
        }
    }

    private boolean isLoyaltyEnabled(UUID clientId, UUID orgId) {
        try {
            var cfg = configService.getConfigurationForClientAndBranch(clientId, orgId);
            return cfg == null || cfg.isLoyaltyEnabled();
        } catch (Exception e) {
            log.warn("Could not check loyalty config — defaulting to enabled", e);
            return true;
        }
    }

    private void clearDefault(UUID clientId, UUID orgId) {
        if (orgId != null) {
            programRepository.clearDefaultForOrg(clientId, orgId);
        } else {
            programRepository.clearDefaultForClient(clientId);
        }
    }

    private Optional<LoyaltyProgram> resolveProgram(UUID customerId, UUID clientId, UUID orgId) {
        // 1. Branch-level active program
        if (orgId != null) {
            List<LoyaltyProgram> branchActive = programRepository.findByClientIdAndOrgIdAndIsActiveTrueOrderByPriorityDesc(clientId, orgId);
            if (!branchActive.isEmpty()) return branchActive.stream().findFirst();
        }

        // 2. Client-level active program
        List<LoyaltyProgram> clientActive = programRepository.findByClientIdAndOrgIdIsNullAndIsActiveTrueOrderByPriorityDesc(clientId);
        if (!clientActive.isEmpty()) return clientActive.stream().findFirst();

        // 3. Branch-level default program
        if (orgId != null) {
            Optional<LoyaltyProgram> branchDefault = programRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId);
            if (branchDefault.isPresent()) return branchDefault;
        }

        // 4. Client-level default program
        Optional<LoyaltyProgram> clientDefault = programRepository.findByClientIdAndIsDefaultTrueAndOrgIdIsNull(clientId);
        if (clientDefault.isPresent()) return clientDefault;

        return Optional.empty();
    }

    private Optional<CustomerLoyalty> findAccount(UUID customerId, UUID clientId, UUID orgId) {
        return accountRepository.findByCustomerIdAndClientId(customerId, clientId);
    }

    private CustomerLoyalty getOrCreateAccount(UUID customerId, UUID clientId, UUID orgId, UUID programId) {
        return findAccount(customerId, clientId, orgId)
                .orElseGet(() -> accountRepository.save(CustomerLoyalty.builder()
                        .customerId(customerId)
                        .clientId(clientId)
                        .orgId(orgId)
                        .programId(programId)
                        .build()));
    }
}
