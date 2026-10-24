package com.restaurant.pos.accounting.service;

import com.restaurant.pos.accounting.domain.AccountType;
import com.restaurant.pos.accounting.domain.AccountingAccount;
import com.restaurant.pos.accounting.domain.AccountingPostingJob;
import com.restaurant.pos.accounting.domain.JournalEntry;
import com.restaurant.pos.accounting.domain.JournalLine;
import com.restaurant.pos.accounting.domain.JournalStatus;
import com.restaurant.pos.accounting.dto.AccountingBackfillRequest;
import com.restaurant.pos.accounting.dto.AccountingBackfillResponse;
import com.restaurant.pos.accounting.repository.AccountingAccountRepository;
import com.restaurant.pos.accounting.repository.AccountingPostingJobRepository;
import com.restaurant.pos.accounting.repository.JournalEntryRepository;
import com.restaurant.pos.accounting.repository.PartyLedgerEntryRepository;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountingPostingServiceTest {

    @Test
    void cleanupAutoPostedAccountingDataDeletesDependentsBeforeJournalEntries() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        AccountingPostingJobRepository postingJobRepository = mock(AccountingPostingJobRepository.class);
        PartyLedgerEntryRepository partyLedgerEntryRepository = mock(PartyLedgerEntryRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        when(journalEntryRepository.bulkDeleteAutoPostedByClientIdAndOrgId(clientId, orgId)).thenReturn(3);

        AccountingPostingService service = new AccountingPostingService(
                mock(AccountingService.class),
                mock(AccountingDefaultsService.class),
                mock(AccountingAccountRepository.class),
                journalEntryRepository,
                partyLedgerEntryRepository,
                postingJobRepository,
                mock(OrderRepository.class),
                mock(InvoiceRepository.class),
                mock(com.restaurant.pos.common.context.TimezoneResolver.class),
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                mock(com.restaurant.pos.expense.repository.ExpenseRepository.class),
                mock(PlatformTransactionManager.class), new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );

        int deleted = service.cleanupAutoPostedAccountingData(clientId, orgId);

        assertThat(deleted).isEqualTo(3);

        InOrder cleanupOrder = inOrder(postingJobRepository, partyLedgerEntryRepository, journalEntryRepository);
        cleanupOrder.verify(postingJobRepository).bulkDeleteByClientIdAndOrgId(clientId, orgId);
        cleanupOrder.verify(partyLedgerEntryRepository).bulkDeleteForAutoPostedJournalsByClientIdAndOrgId(clientId, orgId);
        cleanupOrder.verify(journalEntryRepository).bulkDeleteAutoPostedLinesByClientIdAndOrgId(clientId, orgId);
        cleanupOrder.verify(journalEntryRepository).bulkDeleteAutoPostedByClientIdAndOrgId(clientId, orgId);
    }

    @Test
    void internalPostingTransactionUsesRequiresNew() throws Exception {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());

        AccountingPostingService service = new AccountingPostingService(
                mock(AccountingService.class),
                mock(AccountingDefaultsService.class),
                mock(AccountingAccountRepository.class),
                mock(JournalEntryRepository.class),
                mock(PartyLedgerEntryRepository.class),
                mock(AccountingPostingJobRepository.class),
                mock(OrderRepository.class),
                mock(InvoiceRepository.class),
                mock(com.restaurant.pos.common.context.TimezoneResolver.class),
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                mock(com.restaurant.pos.expense.repository.ExpenseRepository.class),
                transactionManager, new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );

        Method method = AccountingPostingService.class.getDeclaredMethod("inTransaction", Supplier.class);
        method.setAccessible(true);

        Object result = method.invoke(service, (Supplier<String>) () -> "posted");

        assertThat(result).isEqualTo("posted");
        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void backfillRangeAllowsMoreThanOneYear() throws Exception {
        AccountingPostingService service = new AccountingPostingService(
                mock(AccountingService.class),
                mock(AccountingDefaultsService.class),
                mock(AccountingAccountRepository.class),
                mock(JournalEntryRepository.class),
                mock(PartyLedgerEntryRepository.class),
                mock(AccountingPostingJobRepository.class),
                mock(OrderRepository.class),
                mock(InvoiceRepository.class),
                mock(com.restaurant.pos.common.context.TimezoneResolver.class),
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                mock(com.restaurant.pos.expense.repository.ExpenseRepository.class),
                mock(PlatformTransactionManager.class), new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );

        Method method = AccountingPostingService.class.getDeclaredMethod("boundedRange", LocalDateTime.class, LocalDateTime.class);
        method.setAccessible(true);

        Object range = method.invoke(
                service,
                LocalDateTime.parse("2024-01-01T00:00:00"),
                LocalDateTime.parse("2026-05-27T23:59:00")
        );

        assertThat(range).isNotNull();
    }

    @Test
    void replaceInvoiceJournalUsesBranchScopeAndLineDiscountFallback() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID viewingOrgId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(viewingOrgId);

        AccountingService accountingService = mock(AccountingService.class);
        AccountingDefaultsService defaultsService = mock(AccountingDefaultsService.class);
        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        AccountingPostingJobRepository postingJobRepository = mock(AccountingPostingJobRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());

        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));

        AccountingPostingService service = new AccountingPostingService(
                accountingService,
                defaultsService,
                accountRepository,
                journalEntryRepository,
                mock(PartyLedgerEntryRepository.class),
                postingJobRepository,
                orderRepository,
                invoiceRepository,
                timezoneResolver,
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                mock(com.restaurant.pos.expense.repository.ExpenseRepository.class),
                transactionManager, new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );

        AccountingAccount receivable = account(AccountingDefaultsService.ACCOUNTS_RECEIVABLE, AccountType.ASSET);
        AccountingAccount discount = account(AccountingDefaultsService.DISCOUNT_ALLOWED, AccountType.EXPENSE);
        AccountingAccount revenue = account(AccountingDefaultsService.SALES_REVENUE, AccountType.INCOME);
        AccountingAccount tax = account(AccountingDefaultsService.OUTPUT_TAX, AccountType.LIABILITY);
        when(defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_RECEIVABLE)).thenReturn(receivable);
        when(defaultsService.resolveAccount(AccountingDefaultsService.DISCOUNT_ALLOWED)).thenReturn(discount);
        when(defaultsService.resolveAccount(AccountingDefaultsService.SALES_REVENUE)).thenReturn(revenue);
        when(defaultsService.resolveAccount(AccountingDefaultsService.OUTPUT_TAX)).thenReturn(tax);

        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderNo("SO-1")
                .orderStatus("COMPLETED")
                .grandTotal(new BigDecimal("68.00"))
                .totalTaxAmount(new BigDecimal("18.00"))
                .totalDiscountAmount(BigDecimal.ZERO)
                .lines(List.of(OrderLine.builder()
                        .discountAmount(new BigDecimal("50.00"))
                        .build()))
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);

        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .invoiceType(InvoiceType.CUSTOMER_INVOICE)
                .orderId(orderId)
                .invoiceNo("INV-1")
                .invoiceDate(LocalDateTime.parse("2026-05-23T01:28:00"))
                .status("PAID")
                .totalAmount(new BigDecimal("68.00"))
                .amountDue(BigDecimal.ZERO)
                .build();
        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        JournalEntry oldEntry = JournalEntry.builder()
                .id(UUID.randomUUID())
                .sourceType("CUSTOMER_INVOICE")
                .sourceId(invoiceId)
                .status(JournalStatus.POSTED)
                .autoPosted(true)
                .description("Original invoice")
                .build();
        oldEntry.setClientId(clientId);
        oldEntry.setOrgId(orgId);

        when(journalEntryRepository.findActiveBySourceWithLines(clientId, orgId, "CUSTOMER_INVOICE", invoiceId, JournalStatus.POSTED))
                .thenReturn(List.of(oldEntry));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        AccountingPostingJob postingJob = AccountingPostingJob.builder()
                .sourceType("CUSTOMER_INVOICE")
                .sourceId(invoiceId)
                .status("PENDING")
                .attemptCount(0)
                .build();
        postingJob.setClientId(clientId);
        postingJob.setOrgId(orgId);
        when(postingJobRepository.findLockedBySource(clientId, orgId, "CUSTOMER_INVOICE", invoiceId))
                .thenReturn(List.of(postingJob));
        when(accountingService.createJournalEntry(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            entry.setId(UUID.randomUUID());
            return entry;
        });

        AccountingPostingService.PostingOutcome outcome = service.replaceInvoiceJournal(order, invoice, "Discount corrected");

        assertThat(outcome).isEqualTo(AccountingPostingService.PostingOutcome.POSTED);
        assertThat(oldEntry.getStatus()).isEqualTo(JournalStatus.VOID);
        assertThat(oldEntry.getIsactive()).isEqualTo("N");
        assertThat(TenantContext.getCurrentOrg()).isEqualTo(viewingOrgId);
        ArgumentCaptor<JournalEntry> journalCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        InOrder repostOrder = inOrder(journalEntryRepository, accountingService);
        repostOrder.verify(journalEntryRepository).saveAndFlush(oldEntry);
        repostOrder.verify(accountingService).createJournalEntry(journalCaptor.capture());
        JournalEntry corrected = journalCaptor.getValue();
        assertThat(corrected.getOrgId()).isEqualTo(orgId);
        assertThat(corrected.getSourceType()).isEqualTo("CUSTOMER_INVOICE");
        assertThat(corrected.getSourceId()).isEqualTo(invoiceId);
        assertThat(debit(corrected, receivable)).isEqualByComparingTo("68.00");
        assertThat(debit(corrected, discount)).isEqualByComparingTo("50.00");
        assertThat(credit(corrected, revenue)).isEqualByComparingTo("100.00");
        assertThat(credit(corrected, tax)).isEqualByComparingTo("18.00");

        TenantContext.clear();
    }

    @Test
    void postSaleCogsReusesExistingPostingJobWithoutDuplicateInsert() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingPostingJob existingJob = AccountingPostingJob.builder()
                .sourceType("SALE_COGS")
                .sourceId(orderId)
                .status("FAILED")
                .attemptCount(2)
                .lastError("previous failure")
                .build();
        existingJob.setClientId(clientId);
        existingJob.setOrgId(orgId);

        AccountingPostingJobRepository postingJobRepository = mock(AccountingPostingJobRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(journalEntryRepository.findActiveBySource(clientId, orgId, "SALE_COGS", orderId, JournalStatus.POSTED))
                .thenReturn(List.of());
        when(postingJobRepository.findLockedBySource(clientId, orgId, "SALE_COGS", orderId))
                .thenReturn(List.of(existingJob));

        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("COMPLETED")
                .orderNo("SO-COGS")
                .lines(List.of())
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));

        AccountingPostingService service = new AccountingPostingService(
                mock(AccountingService.class),
                mock(AccountingDefaultsService.class),
                mock(AccountingAccountRepository.class),
                journalEntryRepository,
                mock(PartyLedgerEntryRepository.class),
                postingJobRepository,
                orderRepository,
                mock(InvoiceRepository.class),
                timezoneResolver,
                mock(PaymentRepository.class),
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                mock(com.restaurant.pos.expense.repository.ExpenseRepository.class),
                transactionManager, new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );

        AccountingPostingService.PostingOutcome outcome = service.postSaleCogs(order);

        assertThat(outcome).isEqualTo(AccountingPostingService.PostingOutcome.SKIPPED);
        assertThat(existingJob.getAttemptCount()).isEqualTo(3);
        assertThat(existingJob.getStatus()).isEqualTo("SKIPPED");
        assertThat(existingJob.getLastError()).isNull();
        verify(postingJobRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());

        TenantContext.clear();
    }

    @Test
    void backfillRepairsVoidedInactiveSalePaymentWithMissingReversal() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID originalJournalId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        AccountingService accountingService = mock(AccountingService.class);
        AccountingAccountRepository accountRepository = mock(AccountingAccountRepository.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        AccountingPostingJobRepository postingJobRepository = mock(AccountingPostingJobRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        com.restaurant.pos.expense.repository.ExpenseRepository expenseRepository = mock(com.restaurant.pos.expense.repository.ExpenseRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());

        AccountingAccount cash = account(AccountingDefaultsService.CASH, AccountType.ASSET);
        AccountingAccount receivable = account(AccountingDefaultsService.ACCOUNTS_RECEIVABLE, AccountType.ASSET);
        when(accountRepository.findByIdAndClientIdAndOrgId(cash.getId(), clientId, orgId)).thenReturn(Optional.of(cash));
        when(accountRepository.findByIdAndClientIdAndOrgId(receivable.getId(), clientId, orgId)).thenReturn(Optional.of(receivable));

        Order voidedOrder = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("CANCELLED")
                .paymentStatus("VOID")
                .orderDate(Instant.parse("2026-06-06T01:00:00Z"))
                .build();
        voidedOrder.setClientId(clientId);
        voidedOrder.setOrgId(orgId);

        Payment inactiveVoidedPayment = Payment.builder()
                .id(paymentId)
                .orderId(orderId)
                .paymentType(PaymentType.INBOUND)
                .paymentMethod("CASH")
                .amountPaid(new BigDecimal("105.00"))
                .docStatus("VOIDED")
                .isactive("N")
                .paymentDate(LocalDateTime.parse("2026-06-06T06:30:00"))
                .build();
        inactiveVoidedPayment.setClientId(clientId);
        inactiveVoidedPayment.setOrgId(orgId);

        JournalEntry original = JournalEntry.builder()
                .id(originalJournalId)
                .entryNo("AUTO-INBOUND_PAYMENT-1")
                .entryDate(LocalDateTime.parse("2026-06-06T06:30:00"))
                .sourceType("INBOUND_PAYMENT")
                .sourceId(paymentId)
                .status(JournalStatus.POSTED)
                .autoPosted(true)
                .build();
        original.setClientId(clientId);
        original.setOrgId(orgId);
        original.attachLine(JournalLine.builder()
                .accountId(cash.getId())
                .debit(new BigDecimal("105.00"))
                .credit(BigDecimal.ZERO)
                .description("Cash received")
                .build());
        original.attachLine(JournalLine.builder()
                .accountId(receivable.getId())
                .debit(BigDecimal.ZERO)
                .credit(new BigDecimal("105.00"))
                .description("Receivable settled")
                .build());

        when(orderRepository.findByClientIdAndOrgIdAndOrderDateBetweenOrderByOrderDateAsc(any(), any(), any(), any()))
                .thenReturn(List.of(voidedOrder));
        when(orderRepository.findByIdWithLines(orderId)).thenReturn(Optional.of(voidedOrder));
        when(expenseRepository.findByClientIdAndOrgIdAndExpenseDateBetweenOrderByExpenseDateAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(paymentRepository.findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(paymentRepository.findByOrderId(orderId)).thenReturn(List.of(inactiveVoidedPayment));
        when(paymentRepository.findByExpenseId(any())).thenReturn(List.of());
        when(journalEntryRepository.findActiveBySourceWithLines(clientId, orgId, "INBOUND_PAYMENT", paymentId, JournalStatus.POSTED))
                .thenReturn(List.of(original));
        when(journalEntryRepository.findActiveBySourceWithLines(clientId, orgId, "INBOUND_PAYMENT_REV", originalJournalId, JournalStatus.POSTED))
                .thenReturn(List.of());
        AccountingPostingJob reversalJob = AccountingPostingJob.builder()
                .sourceType("INBOUND_PAYMENT_REV")
                .sourceId(originalJournalId)
                .status("PENDING")
                .attemptCount(0)
                .build();
        reversalJob.setClientId(clientId);
        reversalJob.setOrgId(orgId);
        when(postingJobRepository.findLockedBySource(clientId, orgId, "INBOUND_PAYMENT_REV", originalJournalId))
                .thenReturn(List.of(reversalJob));
        when(accountingService.createJournalEntry(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            entry.setId(UUID.randomUUID());
            return entry;
        });

        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));

        AccountingPostingService service = new AccountingPostingService(
                accountingService,
                mock(AccountingDefaultsService.class),
                accountRepository,
                journalEntryRepository,
                mock(PartyLedgerEntryRepository.class),
                postingJobRepository,
                orderRepository,
                mock(InvoiceRepository.class),
                timezoneResolver,
                paymentRepository,
                mock(PaymentSplitRepository.class),
                mock(ProductRepository.class),
                mock(ExpenseCategoryRepository.class),
                mock(StockAdjustmentRepository.class),
                expenseRepository,
                transactionManager, new com.restaurant.pos.common.service.CurrencyScaleService(mock(com.restaurant.pos.purchasing.repository.CurrencyRepository.class))
        );
        AccountingBackfillRequest request = new AccountingBackfillRequest();
        request.setFrom("2026-06-06T00:00:00");
        request.setTo("2026-06-06T23:59:59");
        request.setSourceTypes(Set.of("PAYMENT"));

        AccountingBackfillResponse response = service.backfill(request);

        assertThat(response.getReversed()).isEqualTo(1);
        ArgumentCaptor<JournalEntry> journalCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(accountingService).createJournalEntry(journalCaptor.capture());
        JournalEntry reversal = journalCaptor.getValue();
        assertThat(reversal.getSourceType()).isEqualTo("INBOUND_PAYMENT_REV");
        assertThat(reversal.getSourceId()).isEqualTo(originalJournalId);
        assertThat(reversal.getReversalOfJournalEntryId()).isEqualTo(originalJournalId);
        assertThat(debit(reversal, receivable)).isEqualByComparingTo("105.00");
        assertThat(credit(reversal, cash)).isEqualByComparingTo("105.00");
        verify(paymentRepository).save(inactiveVoidedPayment);

        TenantContext.clear();
    }

    private AccountingAccount account(String systemKey, AccountType type) {
        AccountingAccount account = AccountingAccount.builder()
                .id(UUID.randomUUID())
                .code(systemKey)
                .name(systemKey)
                .accountType(type)
                .systemKey(systemKey)
                .build();
        account.setClientId(UUID.randomUUID());
        account.setOrgId(UUID.randomUUID());
        return account;
    }

    private BigDecimal debit(JournalEntry entry, AccountingAccount account) {
        return amount(entry, account, true);
    }

    private BigDecimal credit(JournalEntry entry, AccountingAccount account) {
        return amount(entry, account, false);
    }

    private BigDecimal amount(JournalEntry entry, AccountingAccount account, boolean debit) {
        return entry.getLines().stream()
                .filter(line -> account.getId().equals(line.getAccountId()))
                .map(line -> debit ? line.getDebit() : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
