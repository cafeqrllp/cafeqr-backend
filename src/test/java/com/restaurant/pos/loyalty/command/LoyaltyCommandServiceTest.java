package com.restaurant.pos.loyalty.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.CurrencyScaleService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.domain.CustomerLoyalty;
import com.restaurant.pos.loyalty.domain.LoyaltyEarnRule;
import com.restaurant.pos.loyalty.domain.LoyaltyProgram;
import com.restaurant.pos.loyalty.domain.LoyaltyRedemptionRule;
import com.restaurant.pos.loyalty.domain.LoyaltyTransaction;
import com.restaurant.pos.loyalty.domain.LoyaltyTransactionType;
import com.restaurant.pos.loyalty.mapper.LoyaltyDtoMapper;
import com.restaurant.pos.loyalty.repository.CustomerLoyaltyRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyProgramRepository;
import com.restaurant.pos.loyalty.repository.LoyaltyTransactionRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoyaltyCommandServiceTest {

    private LoyaltyProgramRepository programRepository;
    private CustomerLoyaltyRepository accountRepository;
    private LoyaltyTransactionRepository transactionRepository;
    private LoyaltyCommandService service;

    private final UUID clientId = UUID.randomUUID();
    private final UUID branchA = UUID.randomUUID();
    private final UUID branchB = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        programRepository = mock(LoyaltyProgramRepository.class);
        accountRepository = mock(CustomerLoyaltyRepository.class);
        transactionRepository = mock(LoyaltyTransactionRepository.class);
        SystemConfigurationService configService = mock(SystemConfigurationService.class);
        CurrencyScaleService currencyScale = mock(CurrencyScaleService.class);
        when(currencyScale.decimalPlaces(any(), any())).thenReturn(2);
        when(accountRepository.save(any(CustomerLoyalty.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(LoyaltyTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findByOrderIdAndClientId(any(), any())).thenReturn(List.of());

        service = new LoyaltyCommandService(programRepository, accountRepository, transactionRepository,
                mock(CustomerRepository.class), configService, mock(LoyaltyDtoMapper.class),
                mock(OrderRepository.class), currencyScale);

        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(branchB);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private LoyaltyProgram program(BigDecimal spend, boolean allowPartial) {
        LoyaltyProgram program = LoyaltyProgram.builder().name("Default").isActive(true).isDefault(true).build();
        program.setId(UUID.randomUUID());
        program.getEarnRules().add(LoyaltyEarnRule.builder().program(program)
                .spendAmount(spend).earnPoints(1).build());
        program.getRedemptionRules().add(LoyaltyRedemptionRule.builder().program(program)
                .pointsRequired(100).discountAmount(new BigDecimal("10")).minPoints(0)
                .allowPartial(allowPartial).build());
        when(programRepository.findByClientIdAndOrgIdAndIsDefaultTrueAndIsActiveTrue(clientId, branchB))
                .thenReturn(Optional.of(program));
        return program;
    }

    /** Account opened at branch A; the tenant is at branch B. */
    private CustomerLoyalty accountAtBranchA(int points) {
        CustomerLoyalty account = CustomerLoyalty.builder()
                .id(UUID.randomUUID()).customerId(customerId).clientId(clientId).orgId(branchA)
                .currentPoints(points).lifetimeEarned(points).build();
        when(accountRepository.findByCustomerIdAndClientIdOrderByCreatedAtAsc(customerId, clientId))
                .thenReturn(List.of(account));
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void redeem_atAnotherBranch_usesSharedAccount() {
        program(new BigDecimal("100"), true);
        CustomerLoyalty account = accountAtBranchA(500);

        RedemptionResult result = service.redeemPoints(customerId, orderId, 200);

        assertThat(result.alreadyApplied()).isFalse();
        assertThat(result.discount()).isEqualByComparingTo("20.00");
        assertThat(account.getCurrentPoints()).isEqualTo(300);
    }

    @Test
    void redeem_partialAllowed_isProportional() {
        program(new BigDecimal("100"), true);
        CustomerLoyalty account = accountAtBranchA(500);

        RedemptionResult result = service.redeemPoints(customerId, orderId, 150);

        assertThat(result.discount()).isEqualByComparingTo("15.00");
        assertThat(account.getCurrentPoints()).isEqualTo(350);
    }

    @Test
    void redeem_partialNotAllowed_rejectsNonMultipleWithoutDebiting() {
        program(new BigDecimal("100"), false);
        CustomerLoyalty account = accountAtBranchA(500);

        assertThatThrownBy(() -> service.redeemPoints(customerId, orderId, 150))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("multiples of 100");
        assertThat(account.getCurrentPoints()).isEqualTo(500);
    }

    @Test
    void redeem_insufficientPoints_throwsBusinessExceptionWithReason() {
        program(new BigDecimal("100"), true);
        accountAtBranchA(80);

        assertThatThrownBy(() -> service.redeemPoints(customerId, orderId, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient points. Available: 80");
    }

    @Test
    void redeem_noAccount_throwsBusinessException() {
        program(new BigDecimal("100"), true);
        when(accountRepository.findByCustomerIdAndClientIdOrderByCreatedAtAsc(customerId, clientId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.redeemPoints(customerId, orderId, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no loyalty points");
    }

    @Test
    void redeem_replayForSameOrder_doesNotDebitAgain() {
        program(new BigDecimal("100"), true);
        CustomerLoyalty account = accountAtBranchA(500);
        when(transactionRepository.findByOrderIdAndClientId(orderId, clientId)).thenReturn(List.of(
                LoyaltyTransaction.builder().transactionType(LoyaltyTransactionType.REDEEM).points(-100).build()));

        RedemptionResult result = service.redeemPoints(customerId, orderId, 100);

        assertThat(result.alreadyApplied()).isTrue();
        assertThat(account.getCurrentPoints()).isEqualTo(500);
    }

    @Test
    void earn_withZeroSpendRule_isSkippedInsteadOfFailing() {
        program(BigDecimal.ZERO, true);

        assertThat(service.earnPoints(customerId, orderId, new BigDecimal("500"))).isEmpty();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void earn_creditsSharedAccount() {
        program(new BigDecimal("100"), true);
        CustomerLoyalty account = accountAtBranchA(0);

        Optional<LoyaltyTransaction> txn = service.earnPoints(customerId, orderId, new BigDecimal("550"));

        assertThat(txn).isPresent();
        assertThat(txn.get().getPoints()).isEqualTo(5);
        assertThat(account.getCurrentPoints()).isEqualTo(5);
    }

    @Test
    void reverseEarn_afterPointsSpent_ledgerRecordsOnlyWhatWasRemoved() {
        CustomerLoyalty account = accountAtBranchA(30); // earned 50, already spent 20
        LoyaltyTransaction earn = LoyaltyTransaction.builder()
                .id(UUID.randomUUID()).customerLoyaltyId(account.getId()).customerId(customerId)
                .clientId(clientId).orderId(orderId).transactionType(LoyaltyTransactionType.EARN).points(50).build();
        when(transactionRepository.findByOrderIdAndClientId(orderId, clientId)).thenReturn(List.of(earn));

        service.reverseOrderTransactions(orderId);

        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        LoyaltyTransaction reversal = captor.getValue();
        assertThat(reversal.getTransactionType()).isEqualTo(LoyaltyTransactionType.REVERSAL);
        assertThat(reversal.getPoints()).isEqualTo(-30);
        assertThat(reversal.getBalanceAfter()).isZero();
        assertThat(account.getCurrentPoints()).isZero();
    }
}
