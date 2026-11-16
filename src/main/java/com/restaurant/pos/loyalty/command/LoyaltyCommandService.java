package com.restaurant.pos.loyalty.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.CurrencyScaleService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.domain.*;
import com.restaurant.pos.loyalty.dto.LoyaltyProgramDto;
import com.restaurant.pos.loyalty.mapper.LoyaltyDtoMapper;
import com.restaurant.pos.loyalty.repository.CustomerLoyaltyRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyProgramRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyTransactionRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.domain.Order;

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
    private final CustomerRepository customerRepository;
    private final SystemConfigurationService configService;
    private final LoyaltyDtoMapper mapper;
    private final OrderRepository orderRepository;
    private final CurrencyScaleService currencyScale;

    @Transactional
    public LoyaltyProgramDto createProgram(CreateLoyaltyProgramCommand cmd) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        validateRedemptionLimits(cmd.getMinPoints(), cmd.getMaxPointsPerOrder(), cmd.getPointsRequired(), cmd.isAllowPartial());

        // Inactive programs cannot be default
        if (cmd.isDefault() && !cmd.isActive()) {
            throw new BusinessException("Inactive loyalty programs cannot be marked as default. Only active programs can be set as default.");
        }

        // Determine scope: client-wide (orgId = null) or branch-specific
        if (cmd.isClientWide() && !com.restaurant.pos.common.util.SecurityUtils.isSuperAdmin()) {
            throw new BusinessException("Only Super Admin can create client-wide loyalty programs.");
        }
        UUID targetOrgId = cmd.isClientWide() ? null : orgId;

        // Clear existing default in the same scope before setting new one
        if (cmd.isDefault()) {
            clearDefault(clientId, targetOrgId);
        }

        LoyaltyProgram program = LoyaltyProgram.builder()
                .name(cmd.getName())
                .description(cmd.getDescription())
                .isActive(cmd.isActive())
                .isDefault(cmd.isDefault())
                .priority(cmd.getPriority())
                .build();
        program.setClientId(clientId);
        program.setOrgId(targetOrgId);
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
        UUID clientId = TenantContext.getCurrentTenant();
        UUID currentOrg = TenantContext.getCurrentOrg();
        // Tenant + branch check: never let one client (or another branch) edit someone else's programme
        LoyaltyProgram program = programRepository.findById(cmd.getId())
                .filter(p -> Objects.equals(p.getClientId(), clientId))
                .filter(p -> currentOrg == null || p.getOrgId() == null || Objects.equals(p.getOrgId(), currentOrg))
                .orElseThrow(() -> new BusinessException("Loyalty programme not found."));

        // If it is a client-wide program or being made client-wide, only Super Admin can edit
        boolean isExistingClientWide = program.getOrgId() == null;
        if ((isExistingClientWide || cmd.isClientWide()) && !com.restaurant.pos.common.util.SecurityUtils.isSuperAdmin()) {
            throw new BusinessException("Only Super Admin can modify client-wide loyalty programs.");
        }

        validateRedemptionLimits(cmd.getMinPoints(), cmd.getMaxPointsPerOrder(), cmd.getPointsRequired(), cmd.isAllowPartial());

        // If inactive/deactivating, automatically strip default
        if (!cmd.isActive()) {
            cmd.setDefault(false);
        }

        // Inactive programs cannot be default
        if (cmd.isDefault() && !cmd.isActive()) {
            throw new BusinessException("Inactive loyalty programs cannot be marked as default. Only active programs can be set as default.");
        }

        // Determine scope: client-wide (orgId = null) or branch-specific
        UUID targetOrgId = cmd.isClientWide() ? null : (program.getOrgId() != null ? program.getOrgId() : TenantContext.getCurrentOrg());

        // Clear existing default in the same scope before setting new one
        // Clear the old default in the target scope when this becomes default there — including
        // when an existing default moves between branch and client-wide scope.
        boolean scopeChanged = !Objects.equals(program.getOrgId(), targetOrgId);
        if (cmd.isDefault() && (!program.isDefault() || scopeChanged)) {
            // clearDefault() clears the persistence context (detaching `program`); load the rule
            // collections first so replacing them below cannot hit a LazyInitializationException.
            program.getEarnRules().size();
            program.getRedemptionRules().size();
            clearDefault(clientId, targetOrgId);
        }

        program.setName(cmd.getName());
        program.setDescription(cmd.getDescription());
        program.setActive(cmd.isActive());
        program.setDefault(cmd.isDefault());
        program.setPriority(cmd.getPriority());
        program.setOrgId(targetOrgId);

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
        if (earnRule.getSpendAmount() == null || earnRule.getSpendAmount().signum() <= 0
                || earnRule.getEarnPoints() <= 0) {
            log.warn("Loyalty EARN skipped: programme {} has an invalid earn rule (spend={}, points={})",
                    program.getId(), earnRule.getSpendAmount(), earnRule.getEarnPoints());
            return Optional.empty();
        }

        int points = eligibleAmount
                .divideToIntegralValue(earnRule.getSpendAmount())
                .multiply(BigDecimal.valueOf(earnRule.getEarnPoints()))
                .intValue();

        if (points <= 0) return Optional.empty();

        // Lock the account first so the duplicate check below cannot race with a parallel earn.
        CustomerLoyalty account = lockForUpdate(getOrCreateAccount(customerId, clientId, orgId, program.getId()));

        if (orderId != null && hasTransaction(orderId, clientId, LoyaltyTransactionType.EARN)) {
            log.info("Loyalty EARN already processed for orderId={}", orderId);
            return Optional.empty();
        }

        account.setProgramId(program.getId());
        account.creditPoints(points);
        accountRepository.save(account);
        syncCustomerEntityPoints(customerId, account.getCurrentPoints());

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
                .createdAt(resolveTransactionTime(orderId))
                .build();

        LoyaltyTransaction saved = transactionRepository.save(txn);
        log.info("Loyalty EARN: customer={} order={} points=+{} balance={}", customerId, orderId, points, account.getCurrentPoints());
        return Optional.of(saved);
    }

    /**
     * Same as {@link #earnPoints} but in its own transaction. Used after the order has committed,
     * so an earn failure rolls back only the loyalty write and never the sale.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LoyaltyTransaction> earnPointsInNewTransaction(UUID customerId, UUID orderId, BigDecimal eligibleAmount) {
        return earnPoints(customerId, orderId, eligibleAmount);
    }

    /**
     * Debits loyalty points for an order and returns the discount they are worth.
     * <p>
     * Every rule violation throws {@link BusinessException} with a cashier-readable message, so
     * the calling order transaction rolls back and the POS shows the reason.
     * <p>
     * Points are shared across all branches of a client (one account per customer per client);
     * the redemption rate comes from the active default programme of the current branch.
     */
    @Transactional
    public RedemptionResult redeemPoints(UUID customerId, UUID orderId, int pointsToUse) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId    = TenantContext.getCurrentOrg();

        if (customerId == null) {
            throw new BusinessException("Select a customer to redeem loyalty points.");
        }
        if (pointsToUse <= 0) {
            throw new BusinessException("Points to redeem must be greater than zero.");
        }
        if (!isLoyaltyEnabled(clientId, orgId)) {
            throw new BusinessException("Loyalty is not enabled for this organisation.");
        }

        CustomerLoyalty account = findAccount(customerId, clientId)
                .map(this::lockForUpdate)
                .orElseThrow(() -> new BusinessException("This customer has no loyalty points to redeem."));

        if (orderId != null && hasTransaction(orderId, clientId, LoyaltyTransactionType.REDEEM)) {
            log.info("Loyalty REDEEM already processed for orderId={}", orderId);
            return RedemptionResult.replay();
        }

        LoyaltyProgram program = resolveProgram(customerId, clientId, orgId).orElse(null);
        if (program == null || program.getRedemptionRules().isEmpty()) {
            throw new BusinessException("No active default loyalty programme is configured.");
        }

        LoyaltyRedemptionRule rule = program.getRedemptionRules().get(0);
        if (rule.getPointsRequired() <= 0 || rule.getDiscountAmount() == null
                || rule.getDiscountAmount().signum() <= 0) {
            throw new BusinessException("The loyalty redemption rule is not configured correctly. Please check the loyalty programme.");
        }

        if (account.getCurrentPoints() < rule.getMinPoints()) {
            throw new BusinessException("Minimum " + rule.getMinPoints() + " points required. Available: " + account.getCurrentPoints());
        }
        if (pointsToUse > account.getCurrentPoints()) {
            throw new BusinessException("Insufficient points. Available: " + account.getCurrentPoints());
        }
        if (rule.getMaxPointsPerOrder() != null && pointsToUse > rule.getMaxPointsPerOrder()) {
            throw new BusinessException("Maximum " + rule.getMaxPointsPerOrder() + " points per order.");
        }

        int dp = currencyScale.decimalPlaces(clientId, orgId);
        BigDecimal discount;
        if (rule.isAllowPartial()) {
            // Proportional value: e.g. 100 pts = 10 off, so 150 pts = 15 off.
            discount = rule.getDiscountAmount()
                    .multiply(BigDecimal.valueOf(pointsToUse))
                    .divide(BigDecimal.valueOf(rule.getPointsRequired()), dp, RoundingMode.HALF_UP);
        } else {
            if (pointsToUse % rule.getPointsRequired() != 0) {
                throw new BusinessException("Points must be redeemed in multiples of " + rule.getPointsRequired() + ".");
            }
            discount = rule.getDiscountAmount()
                    .multiply(BigDecimal.valueOf(pointsToUse / rule.getPointsRequired()))
                    .setScale(dp, RoundingMode.HALF_UP);
        }
        if (discount.signum() <= 0) {
            throw new BusinessException("Too few points selected to give a discount.");
        }

        account.setProgramId(program.getId());
        account.debitPoints(pointsToUse);
        accountRepository.save(account);
        syncCustomerEntityPoints(customerId, account.getCurrentPoints());

        transactionRepository.save(LoyaltyTransaction.builder()
                .customerLoyaltyId(account.getId())
                .customerId(customerId)
                .clientId(clientId)
                .orgId(orgId)
                .programId(program.getId())
                .orderId(orderId)
                .transactionType(LoyaltyTransactionType.REDEEM)
                .points(-pointsToUse)
                .balanceAfter(account.getCurrentPoints())
                .remarks("Redeemed " + pointsToUse + " points for " + discount.toPlainString() + " discount")
                .createdAt(resolveTransactionTime(orderId))
                .build());

        log.info("Loyalty REDEEM: customer={} order={} points=-{} discount={}", customerId, orderId, pointsToUse, discount);
        return RedemptionResult.applied(discount);
    }

    @Transactional
    public void reverseOrderTransactions(UUID orderId) {
        reverseOrderTransactions(orderId, null, null);
    }

    @Transactional
    public void reverseOrderTransactions(UUID orderId, UUID explicitClientId, UUID explicitOrgId) {
        if (orderId == null) {
            return;
        }

        UUID clientId = explicitClientId != null ? explicitClientId : TenantContext.getCurrentTenant();
        UUID orgId    = explicitOrgId != null ? explicitOrgId : TenantContext.getCurrentOrg();

        List<LoyaltyTransaction> originals = clientId != null
                ? transactionRepository.findByOrderIdAndClientId(orderId, clientId)
                : transactionRepository.findByOrderId(orderId);
        if (originals == null || originals.isEmpty()) {
            log.info("No loyalty transactions found to reverse for orderId={}", orderId);
            return;
        }

        // Avoid double-reversal by collecting already reversed references
        Set<UUID> alreadyReversedIds = originals.stream()
                .filter(t -> t.getTransactionType() == LoyaltyTransactionType.REVERSAL)
                .map(LoyaltyTransaction::getReferenceTransactionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (LoyaltyTransaction original : originals) {
            if (original.getTransactionType() == LoyaltyTransactionType.REVERSAL) {
                continue;
            }
            if (alreadyReversedIds.contains(original.getId())) {
                log.info("Loyalty transaction {} already reversed, skipping", original.getId());
                continue;
            }

            CustomerLoyalty account = accountRepository.findByIdForUpdate(original.getCustomerLoyaltyId())
                    .orElse(null);
            if (account == null) {
                log.warn("Loyalty account not found for transaction id={}, customerLoyaltyId={}",
                        original.getId(), original.getCustomerLoyaltyId());
                continue;
            }

            int reversalPoints;
            String remarks = "Reversal of " + original.getTransactionType() + " on order cancellation/refund";
            if (original.getTransactionType() == LoyaltyTransactionType.EARN) {
                int removed = account.reverseEarnedPoints(original.getPoints());
                reversalPoints = -removed;
                if (removed < Math.abs(original.getPoints())) {
                    remarks += " (only " + removed + " of " + Math.abs(original.getPoints())
                            + " points reversed; the rest were already spent)";
                    log.warn("Loyalty REVERSAL partial: order={} earned={} reversed={}",
                            orderId, original.getPoints(), removed);
                }
            } else if (original.getTransactionType() == LoyaltyTransactionType.REDEEM) {
                reversalPoints = Math.abs(original.getPoints());
                account.reverseRedeemedPoints(reversalPoints);
            } else {
                reversalPoints = -original.getPoints();
                if (reversalPoints > 0) {
                    account.creditPoints(reversalPoints);
                } else {
                    account.debitPoints(Math.abs(reversalPoints));
                }
            }
            accountRepository.save(account);
            syncCustomerEntityPoints(original.getCustomerId(), account.getCurrentPoints());

            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerLoyaltyId(account.getId())
                    .customerId(original.getCustomerId())
                    .clientId(clientId != null ? clientId : original.getClientId())
                    .orgId(orgId != null ? orgId : original.getOrgId())
                    .programId(original.getProgramId())
                    .orderId(orderId)
                    .transactionType(LoyaltyTransactionType.REVERSAL)
                    .points(reversalPoints)
                    .balanceAfter(account.getCurrentPoints())
                    .referenceTransactionId(original.getId())
                    .remarks(remarks)
                    .createdAt(resolveTransactionTime(orderId))
                    .build());

            log.info("Loyalty REVERSAL: original={} order={} points={}", original.getId(), orderId, reversalPoints);
        }
    }

    private Instant resolveTransactionTime(UUID orderId) {
        if (orderId == null || orderRepository == null) {
            return Instant.now();
        }
        try {
            return orderRepository.findById(orderId)
                    .map(o -> {
                        if (o.getOrderDate() != null) {
                            return o.getOrderDate();
                        }
                        if (o.getCreatedAt() != null) {
                            return o.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
                        }
                        return Instant.now();
                    })
                    .orElseGet(Instant::now);
        } catch (Exception ex) {
            return Instant.now();
        }
    }

    private void syncCustomerEntityPoints(UUID customerId, int points) {
        if (customerId == null || customerRepository == null) return;
        try {
            customerRepository.findById(customerId).ifPresent(c -> {
                c.setLoyaltyPoints(points);
                customerRepository.save(c);
            });
        } catch (Exception ex) {
            log.warn("Failed to sync loyalty points to Customer entity for customerId={}", customerId, ex);
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
        // 1. Branch-level active + default program
        if (orgId != null) {
            Optional<LoyaltyProgram> branchDefault = programRepository.findByClientIdAndOrgIdAndIsDefaultTrueAndIsActiveTrue(clientId, orgId);
            if (branchDefault.isPresent()) return branchDefault;
        }

        // 2. Client-wide active + default program (org_id IS NULL)
        Optional<LoyaltyProgram> clientDefault = programRepository.findByClientIdAndOrgIdIsNullAndIsDefaultTrueAndIsActiveTrue(clientId);
        if (clientDefault.isPresent()) return clientDefault;

        // 3. No active default program found — do NOT fall back to non-default programs
        return Optional.empty();
    }

    /** Rejects rule combinations under which customers could never redeem points. */
    private void validateRedemptionLimits(int minPoints, Integer maxPointsPerOrder, int pointsRequired, boolean allowPartial) {
        if (minPoints < 0) {
            throw new BusinessException("Minimum points to redeem cannot be negative.");
        }
        if (maxPointsPerOrder != null) {
            if (maxPointsPerOrder < minPoints) {
                throw new BusinessException("Max points per bill (" + maxPointsPerOrder
                        + ") cannot be less than the minimum points needed (" + minPoints + ").");
            }
            if (!allowPartial && maxPointsPerOrder < pointsRequired) {
                throw new BusinessException("Max points per bill (" + maxPointsPerOrder
                        + ") must be at least " + pointsRequired + " when points are used in whole blocks.");
            }
        }
    }

    private boolean hasTransaction(UUID orderId, UUID clientId, LoyaltyTransactionType type) {
        return transactionRepository.findByOrderIdAndClientId(orderId, clientId).stream()
                .anyMatch(t -> t.getTransactionType() == type);
    }

    /**
     * One loyalty account per customer per client: points earned at any branch can be used at
     * any branch. If legacy per-branch accounts exist, the oldest one is used.
     */
    private Optional<CustomerLoyalty> findAccount(UUID customerId, UUID clientId) {
        List<CustomerLoyalty> accounts = accountRepository.findByCustomerIdAndClientIdOrderByCreatedAtAsc(customerId, clientId);
        return accounts == null || accounts.isEmpty() ? Optional.empty() : Optional.of(accounts.get(0));
    }

    private CustomerLoyalty lockForUpdate(CustomerLoyalty account) {
        if (account == null || account.getId() == null) {
            return account;
        }
        return accountRepository.findByIdForUpdate(account.getId()).orElse(account);
    }

    private CustomerLoyalty getOrCreateAccount(UUID customerId, UUID clientId, UUID orgId, UUID programId) {
        return findAccount(customerId, clientId)
                .orElseGet(() -> accountRepository.save(CustomerLoyalty.builder()
                        .customerId(customerId)
                        .clientId(clientId)
                        .orgId(orgId)
                        .programId(programId)
                        .build()));
    }
}
