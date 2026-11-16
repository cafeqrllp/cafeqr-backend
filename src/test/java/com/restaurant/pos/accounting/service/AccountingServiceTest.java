package com.restaurant.pos.accounting.service;

import com.restaurant.pos.accounting.domain.AccountType;
import com.restaurant.pos.accounting.domain.AccountingAccount;
import com.restaurant.pos.accounting.domain.JournalEntry;
import com.restaurant.pos.accounting.domain.JournalLine;
import com.restaurant.pos.accounting.domain.PartyLedgerEntry;
import com.restaurant.pos.accounting.domain.PartyType;
import com.restaurant.pos.accounting.domain.JournalStatus;
import com.restaurant.pos.accounting.dto.AccountingSummaryDto;
import com.restaurant.pos.accounting.dto.TrialBalanceRowDto;
import com.restaurant.pos.accounting.repository.AccountingAccountRepository;
import com.restaurant.pos.accounting.repository.JournalEntryRepository;
import com.restaurant.pos.accounting.repository.PartyLedgerEntryRepository;
import com.restaurant.pos.accounting.repository.PaymentAllocationRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountingServiceTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void trialBalanceAllowsRangesLongerThanOneYear() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        AccountingService service = service(accountRepository, journalEntryRepository);

        when(accountRepository.findByClientIdAndOrgIdOrderByCodeAsc(eq(clientId), eq(orgId))).thenReturn(List.of());
        when(journalEntryRepository.findAll(any(Specification.class))).thenReturn(List.of());

        List<TrialBalanceRowDto> rows = service.getTrialBalance(
                LocalDateTime.parse("2024-01-01T00:00:00"),
                LocalDateTime.parse("2026-05-27T23:59:00")
        );

        assertThat(rows).isEmpty();
    }

    @Test
    void trialBalanceRejectsReversedRange() {
        TenantContext.setCurrentTenant(UUID.randomUUID());
        TenantContext.setCurrentOrg(UUID.randomUUID());
        AccountingService service = service(mock(AccountingAccountRepository.class), mock(JournalEntryRepository.class));

        assertThatThrownBy(() -> service.getTrialBalance(
                LocalDateTime.parse("2026-05-27T23:59:00"),
                LocalDateTime.parse("2024-01-01T00:00:00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("before");
    }

    @Test
    void summaryNetsVoidedSaleJournalReversalToZero() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingAccount revenue = account(AccountingDefaultsService.SALES_REVENUE, AccountType.INCOME);
        AccountingAccount outputTax = account(AccountingDefaultsService.OUTPUT_TAX, AccountType.LIABILITY);
        AccountingAccount cash = account(AccountingDefaultsService.CASH, AccountType.ASSET);
        AccountingAccount bank = account(AccountingDefaultsService.BANK_UPI_CLEARING, AccountType.ASSET);
        List<AccountingAccount> accounts = List.of(revenue, outputTax, cash, bank);

        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentSplitRepository paymentSplitRepository = mock(PaymentSplitRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);

        when(accountRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId)).thenReturn(accounts);
        when(journalEntryRepository.sumLineMovements(eq(clientId), eq(orgId), any(), any(), any(), eq(JournalStatus.POSTED)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        movement(revenue.getId(), "100.00", "100.00"),
                        movement(outputTax.getId(), "5.00", "5.00"),
                        movement(cash.getId(), "52.50", "52.50"),
                        movement(bank.getId(), "52.50", "52.50")
                ));
        when(journalEntryRepository.countPostedActive(eq(clientId), eq(orgId), any(), any(), any(), eq(JournalStatus.POSTED)))
                .thenReturn(8L);
        when(orderRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(paymentRepository.findActivePaymentsInPeriod(eq(clientId), eq(orgId), any(), any())).thenReturn(List.of());
        when(paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));

        AccountingService service = new AccountingService(
                accountRepository,
                journalEntryRepository,
                mock(PartyLedgerEntryRepository.class),
                mock(PaymentAllocationRepository.class),
                paymentRepository,
                paymentSplitRepository,
                mock(InvoiceRepository.class),
                orderRepository,
                mock(BranchContextService.class),
                timezoneResolver, new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class)),
                mock(AccountingDefaultsService.class)
        );

        AccountingSummaryDto summary = service.getSummary(
                LocalDateTime.parse("2026-06-06T00:00:00"),
                LocalDateTime.parse("2026-06-06T23:59:59")
        );

        assertThat(summary.getGrossSales()).isEqualByComparingTo("0.00");
        assertThat(summary.getNetSales()).isEqualByComparingTo("0.00");
        assertThat(summary.getBilledTotal()).isEqualByComparingTo("0.00");
        assertThat(summary.getOutputTax()).isEqualByComparingTo("0.00");
        assertThat(summary.getPaymentCollected()).isEqualByComparingTo("0.00");
        assertThat(summary.getCashCollected()).isEqualByComparingTo("0.00");
        assertThat(summary.getBankCollected()).isEqualByComparingTo("0.00");
        assertThat(summary.getProfit()).isEqualByComparingTo("0.00");
    }

    private AccountingService service(
            AccountingAccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository
    ) {
        return new AccountingService(
                accountRepository,
                journalEntryRepository,
                mock(PartyLedgerEntryRepository.class),
                mock(PaymentAllocationRepository.class),
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(InvoiceRepository.class),
                mock(OrderRepository.class),
                mock(BranchContextService.class),
                mock(com.restaurant.pos.common.context.TimezoneResolver.class), new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class)),
                mock(AccountingDefaultsService.class)
        );
    }

    private AccountingAccount account(String systemKey, AccountType type) {
        AccountingAccount account = AccountingAccount.builder()
                .id(UUID.randomUUID())
                .code(systemKey)
                .name(systemKey)
                .accountType(type)
                .systemKey(systemKey)
                .openingBalance(BigDecimal.ZERO)
                .isactive("Y")
                .build();
        account.setClientId(UUID.randomUUID());
        account.setOrgId(UUID.randomUUID());
        return account;
    }

    @Test
    void createAccountPostsOpeningBalanceAgainstOpeningBalanceEquity() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        AccountingDefaultsService defaultsService = mock(AccountingDefaultsService.class);

        AccountingAccount equity = account(AccountingDefaultsService.OPENING_BALANCE_EQUITY, AccountType.EQUITY);
        equity.setClientId(clientId);
        equity.setOrgId(orgId);
        AccountingAccount bank = AccountingAccount.builder()
                .code("bank-1")
                .name("Main Bank")
                .accountType(AccountType.ASSET)
                .openingBalance(new BigDecimal("500.00"))
                .currentBalance(new BigDecimal("999.00"))
                .build();

        when(accountRepository.save(any(AccountingAccount.class))).thenAnswer(invocation -> {
            AccountingAccount saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(accountRepository.findAllById(any())).thenReturn(List.of(bank, equity));
        when(defaultsService.resolveAccount(AccountingDefaultsService.OPENING_BALANCE_EQUITY)).thenReturn(equity);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountingAccount created = service(accountRepository, journalEntryRepository, mock(PartyLedgerEntryRepository.class), defaultsService)
                .createAccount(bank);

        ArgumentCaptor<JournalEntry> journalCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(journalCaptor.capture());
        JournalEntry opening = journalCaptor.getValue();
        assertThat(opening.getSourceType()).isEqualTo(AccountingService.OPENING_BALANCE_SOURCE);
        assertThat(opening.getSourceId()).isEqualTo(created.getId());
        assertThat(opening.getAutoPosted()).isFalse();
        assertThat(opening.getTotalDebit()).isEqualByComparingTo("500.00");
        assertThat(opening.getTotalCredit()).isEqualByComparingTo("500.00");
        assertThat(opening.getLines()).hasSize(2);
        assertThat(opening.getLines().get(0).getAccountId()).isEqualTo(created.getId());
        assertThat(opening.getLines().get(0).getDebit()).isEqualByComparingTo("500.00");
        assertThat(opening.getLines().get(1).getAccountId()).isEqualTo(equity.getId());
        assertThat(opening.getLines().get(1).getCredit()).isEqualByComparingTo("500.00");
        // The balance comes from the journal only - the amount sent by the client is ignored.
        assertThat(created.getCurrentBalance()).isEqualByComparingTo("500.00");
        assertThat(equity.getCurrentBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void systemAccountCannotBeDeactivated() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        AccountingAccount cash = account(AccountingDefaultsService.CASH, AccountType.ASSET);
        cash.setSystemAccount(true);
        when(accountRepository.findByIdAndClientIdAndOrgId(cash.getId(), clientId, orgId)).thenReturn(Optional.of(cash));

        AccountingService service = service(accountRepository, mock(JournalEntryRepository.class),
                mock(PartyLedgerEntryRepository.class), mock(AccountingDefaultsService.class));

        assertThatThrownBy(() -> service.deactivateAccount(cash.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("System accounts");
        assertThat(cash.getIsactive()).isEqualTo("Y");
    }

    @Test
    void backdatedPartyEntryRecalculatesLaterRunningBalances() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        PartyLedgerEntryRepository partyLedgerEntryRepository = mock(PartyLedgerEntryRepository.class);

        AccountingAccount receivable = account(AccountingDefaultsService.ACCOUNTS_RECEIVABLE, AccountType.ASSET);
        AccountingAccount sales = account(AccountingDefaultsService.SALES_REVENUE, AccountType.INCOME);
        for (AccountingAccount account : List.of(receivable, sales)) {
            account.setClientId(clientId);
            account.setOrgId(orgId);
        }
        when(accountRepository.findAllById(any())).thenReturn(List.of(receivable, sales));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Ledger already holds a later invoice of 100.
        PartyLedgerEntry later = PartyLedgerEntry.builder()
                .partyType(PartyType.CUSTOMER)
                .partyId(customerId)
                .entryDate(LocalDateTime.parse("2026-06-10T10:00:00"))
                .debit(new BigDecimal("100.00"))
                .credit(BigDecimal.ZERO)
                .balanceAfter(new BigDecimal("100.00"))
                .build();
        when(partyLedgerEntryRepository.findTopByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateDescCreatedAtDesc(
                clientId, orgId, PartyType.CUSTOMER, customerId)).thenReturn(Optional.of(later));
        List<PartyLedgerEntry> stored = new ArrayList<>();
        when(partyLedgerEntryRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<PartyLedgerEntry> entries = invocation.getArgument(0);
            entries.forEach(stored::add);
            return stored;
        });
        when(partyLedgerEntryRepository.findByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateAscCreatedAtAsc(
                clientId, orgId, PartyType.CUSTOMER, customerId)).thenAnswer(invocation -> {
            List<PartyLedgerEntry> ledger = new ArrayList<>(stored);
            ledger.add(later);
            return ledger;
        });

        // A backfilled invoice of 50 dated before the existing one.
        JournalEntry entry = JournalEntry.builder()
                .entryNo("JE-BACKDATED")
                .entryDate(LocalDateTime.parse("2026-06-01T10:00:00"))
                .lines(new ArrayList<>())
                .build();
        entry.setOrgId(orgId);
        entry.attachLine(JournalLine.builder().accountId(receivable.getId())
                .debit(new BigDecimal("50.00")).credit(BigDecimal.ZERO)
                .partyType(PartyType.CUSTOMER).partyId(customerId).build());
        entry.attachLine(JournalLine.builder().accountId(sales.getId())
                .debit(BigDecimal.ZERO).credit(new BigDecimal("50.00")).build());

        service(accountRepository, journalEntryRepository, partyLedgerEntryRepository, mock(AccountingDefaultsService.class))
                .createJournalEntry(entry);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getBalanceAfter()).isEqualByComparingTo("50.00");
        assertThat(later.getBalanceAfter()).isEqualByComparingTo("150.00");
    }

    private AccountingService service(
            AccountingAccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository,
            PartyLedgerEntryRepository partyLedgerEntryRepository,
            AccountingDefaultsService defaultsService
    ) {
        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));
        return new AccountingService(
                accountRepository,
                journalEntryRepository,
                partyLedgerEntryRepository,
                mock(PaymentAllocationRepository.class),
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(InvoiceRepository.class),
                mock(OrderRepository.class),
                mock(BranchContextService.class),
                timezoneResolver,
                new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class)),
                defaultsService
        );
    }

    private JournalEntryRepository.AccountMovementProjection movement(UUID accountId, String debit, String credit) {
        BigDecimal debitAmount = new BigDecimal(debit);
        BigDecimal creditAmount = new BigDecimal(credit);
        return new JournalEntryRepository.AccountMovementProjection() {
            @Override
            public UUID getAccountId() {
                return accountId;
            }

            @Override
            public BigDecimal getDebit() {
                return debitAmount;
            }

            @Override
            public BigDecimal getCredit() {
                return creditAmount;
            }
        };
    }
}
