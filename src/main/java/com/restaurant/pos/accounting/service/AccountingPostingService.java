package com.restaurant.pos.accounting.service;

import com.restaurant.pos.accounting.domain.*;
import com.restaurant.pos.accounting.dto.*;
import com.restaurant.pos.accounting.repository.*;
import com.restaurant.pos.category.domain.ExpenseCategory;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockAdjustmentLine;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.*;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPostingService {

    private static final int MAX_BACKFILL_DAYS = 366;

    private final AccountingService accountingService;
    private final AccountingDefaultsService defaultsService;
    private final AccountingAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PartyLedgerEntryRepository partyLedgerEntryRepository;
    private final AccountingPostingJobRepository postingJobRepository;
    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final ProductRepository productRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final ExpenseRepository expenseRepository;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public PostingOutcome postInvoice(Order order, Invoice invoice) {
        if (invoice == null || invoice.getId() == null || isVoid(invoice.getStatus()) || isInactive(invoice.getIsactive())) {
            return PostingOutcome.SKIPPED;
        }
        UUID invoiceId = invoice.getId();
        String sourceType = invoiceSourceType(invoice);
        // Derive orgId from the source entities — critical for Super Admin "All Branches" mode
        UUID sourceOrgId = invoice.getOrgId();
        if (sourceOrgId == null) {
            if (invoice.getExpenseId() != null) {
                sourceOrgId = expenseRepository.findById(invoice.getExpenseId()).map(Expense::getOrgId).orElse(null);
            } else if (order != null) {
                sourceOrgId = order.getOrgId();
            }
        }
        return postAfterCommitOrNow(sourceType, invoiceId, sourceOrgId, () -> {
            Invoice currentInvoice = invoiceRepository.findById(invoiceId).orElse(invoice);
            if (currentInvoice.getExpenseId() != null) {
                Expense expense = expenseRepository.findById(currentInvoice.getExpenseId()).orElse(null);
                return buildInvoiceJournal(null, expense, currentInvoice);
            } else {
                Order currentOrder = resolveOrder(currentInvoice.getOrderId()).orElse(order);
                return buildInvoiceJournal(currentOrder, null, currentInvoice);
            }
        });
    }

    public PostingOutcome replaceInvoiceJournal(Order order, Invoice invoice, String reason) {
        if (invoice == null || invoice.getId() == null || isVoid(invoice.getStatus()) || isInactive(invoice.getIsactive())) {
            return PostingOutcome.SKIPPED;
        }
        UUID invoiceId = invoice.getId();
        String sourceType = invoiceSourceType(invoice);
        UUID sourceOrgId = invoice.getOrgId();
        if (sourceOrgId == null && order != null) {
            sourceOrgId = order.getOrgId();
        }
        return replaceAfterCommitOrNow(sourceType, invoiceId, sourceOrgId, reason, () -> {
            Invoice currentInvoice = invoiceRepository.findById(invoiceId).orElse(invoice);
            if (currentInvoice.getExpenseId() != null) {
                Expense expense = expenseRepository.findById(currentInvoice.getExpenseId()).orElse(null);
                return buildInvoiceJournal(null, expense, currentInvoice);
            }
            Order currentOrder = resolveOrder(currentInvoice.getOrderId()).orElse(order);
            return buildInvoiceJournal(currentOrder, null, currentInvoice);
        });
    }

    public PostingOutcome postPayment(Order order, Payment payment) {
        if (payment == null || payment.getId() == null || isVoid(payment.getDocStatus()) || isInactive(payment.getIsactive())) {
            return PostingOutcome.SKIPPED;
        }
        UUID paymentId = payment.getId();
        String sourceType = paymentSourceType(payment);
        UUID sourceOrgId = payment.getOrgId();
        if (sourceOrgId == null) {
            if (payment.getExpenseId() != null) {
                sourceOrgId = expenseRepository.findById(payment.getExpenseId()).map(Expense::getOrgId).orElse(null);
            } else if (order != null) {
                sourceOrgId = order.getOrgId();
            }
        }
        return postAfterCommitOrNow(sourceType, paymentId, sourceOrgId, () -> {
            Payment currentPayment = paymentRepository.findById(paymentId).orElse(payment);
            if (currentPayment.getExpenseId() != null) {
                Expense expense = expenseRepository.findById(currentPayment.getExpenseId()).orElse(null);
                return buildPaymentJournal(null, expense, currentPayment);
            } else {
                Order currentOrder = resolveOrder(currentPayment.getOrderId()).orElse(order);
                return buildPaymentJournal(currentOrder, null, currentPayment);
            }
        });
    }

    public PostingOutcome postSaleCogs(Order order) {
        if (order == null || order.getId() == null || order.getOrderType() != OrderType.SALE
                || !"COMPLETED".equalsIgnoreCase(order.getOrderStatus())) {
            return PostingOutcome.SKIPPED;
        }
        UUID orderId = order.getId();
        return postAfterCommitOrNow("SALE_COGS", orderId, order.getOrgId(),
                () -> buildSaleCogsJournal(resolveOrder(orderId).orElse(order)));
    }

    public PostingOutcome reverseSaleCogs(Order order, String reason) {
        return order == null ? PostingOutcome.SKIPPED : reverseSourceInScope("SALE_COGS", order.getId(), reason, order.getOrgId());
    }

    public PostingOutcome postStockAdjustment(StockAdjustment adjustment) {
        if (adjustment == null || adjustment.getId() == null || !"COMPLETED".equalsIgnoreCase(adjustment.getStatus())) {
            return PostingOutcome.SKIPPED;
        }
        UUID adjustmentId = adjustment.getId();
        return postAfterCommitOrNow("STOCK_ADJUSTMENT", adjustmentId, adjustment.getOrgId(),
                () -> buildStockAdjustmentJournal(stockAdjustmentRepository.findById(adjustmentId).orElse(adjustment)));
    }

    @Transactional
    public PostingOutcome reverseSource(String sourceType, UUID sourceId, String reason) {
        if (sourceType == null || sourceId == null) {
            return PostingOutcome.SKIPPED;
        }
        Optional<JournalEntry> original = findActiveSourceJournal(sourceType, sourceId);
        if (original.isEmpty()) {
            return PostingOutcome.SKIPPED;
        }
        String reversalSourceType = sourceType + "_REV";
        UUID reversalSourceId = original.get().getId();
        UUID sourceOrgId = original.get().getOrgId();
        return postAfterCommitOrNow(reversalSourceType, reversalSourceId, sourceOrgId, () -> buildReversalJournal(original.get(), reason));
    }

    public PostingOutcome reverseInvoice(Invoice invoice, String reason) {
        return invoice == null ? PostingOutcome.SKIPPED : reverseSourceInScope(invoiceSourceType(invoice), invoice.getId(), reason, invoice.getOrgId());
    }

    public PostingOutcome reversePayment(Payment payment, String reason) {
        return payment == null ? PostingOutcome.SKIPPED : reverseSourceInScope(paymentSourceType(payment), payment.getId(), reason, payment.getOrgId());
    }

    private PostingOutcome reverseSourceInScope(String sourceType, UUID sourceId, String reason, UUID sourceOrgId) {
        UUID previousOrgId = TenantContext.getCurrentOrg();
        if (Objects.equals(previousOrgId, sourceOrgId)) {
            return reverseSource(sourceType, sourceId, reason);
        }
        TenantContext.setCurrentOrg(sourceOrgId);
        try {
            return reverseSource(sourceType, sourceId, reason);
        } finally {
            TenantContext.setCurrentOrg(previousOrgId);
        }
    }

    @Transactional(readOnly = true)
    public List<AccountingPostingErrorDto> getPostingErrors() {
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        List<AccountingPostingJob> jobs = orgId == null
                ? postingJobRepository.findByClientIdAndStatusOrderByUpdatedAtDesc(clientId, "FAILED")
                : postingJobRepository.findByClientIdAndOrgIdAndStatusOrderByUpdatedAtDesc(clientId, orgId, "FAILED");
        return jobs.stream()
                .map(job -> AccountingPostingErrorDto.builder()
                        .id(job.getId())
                        .sourceType(job.getSourceType())
                        .sourceId(job.getSourceId())
                        .status(job.getStatus())
                        .attemptCount(job.getAttemptCount())
                        .lastError(job.getLastError())
                        .updatedAt(job.getUpdatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public AccountingPostingJob retryPosting(UUID jobId) {
        AccountingPostingJob job = postingJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Accounting posting job not found"));
        UUID currentOrgId = TenantContext.getCurrentOrg();
        if (!requireClient().equals(job.getClientId()) || (currentOrgId != null && !Objects.equals(currentOrgId, job.getOrgId()))) {
            throw new ResourceNotFoundException("Accounting posting job not found");
        }
        job.setStatus("PENDING");
        job.setLastError(null);
        postingJobRepository.save(job);
        retrySourceInScope(job);
        return postingJobRepository.findById(jobId).orElse(job);
    }

    // NOT @Transactional: each item posts in its own independent transaction
    // to avoid statement timeout on free-tier databases during bulk backfill
    public AccountingBackfillResponse backfill(AccountingBackfillRequest request) {
      try {
        DateRange range = boundedRange(
                parseBackfillDateTime(request != null ? request.getFrom() : null),
                parseBackfillDateTime(request != null ? request.getTo() : null)
        );
        Set<String> sources = normalizeSourceTypes(request != null ? request.getSourceTypes() : null);
        boolean dryRun = request != null && request.isDryRun();
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        log.info("backfill START | clientId={} | orgId={} | from={} | to={} | sources={} | dryRun={}",
                clientId, orgId, range.from, range.to, sources, dryRun);
        AccountingBackfillResponse response = AccountingBackfillResponse.builder().dryRun(dryRun).build();

        List<Order> ordersInBusinessPeriod = List.of();
        if (sources.contains("INVOICE") || sources.contains("PAYMENT") || sources.contains("COGS")
                || sources.contains("PURCHASE")) {
            ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
            Instant fromInstant = range.from.atZone(zoneId).toInstant();
            Instant toInstant = range.to.atZone(zoneId).toInstant();
            log.info("backfill loading orders | clientId={} | orgId={} | fromInstant={} | toInstant={}", clientId, orgId, fromInstant, toInstant);
            ordersInBusinessPeriod = orderRepository.findByClientIdAndOrgIdAndOrderDateBetweenOrderByOrderDateAsc(clientId, orgId, fromInstant, toInstant);
            log.info("backfill loaded {} orders", ordersInBusinessPeriod.size());
        }

        List<Expense> expensesInBusinessPeriod = List.of();
        if (sources.contains("INVOICE") || sources.contains("PAYMENT") || sources.contains("EXPENSE")) {
            ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
            Instant fromInstant = range.from.atZone(zoneId).toInstant();
            Instant toInstant = range.to.atZone(zoneId).toInstant();
            log.info("backfill loading expenses | clientId={} | orgId={} | fromInstant={} | toInstant={}", clientId, orgId, fromInstant, toInstant);
            expensesInBusinessPeriod = expenseRepository.findByClientIdAndOrgIdAndExpenseDateBetweenOrderByExpenseDateAsc(clientId, orgId, fromInstant, toInstant);
            log.info("backfill loaded {} expenses", expensesInBusinessPeriod.size());
        }

        if (sources.contains("INVOICE")) {
            Set<UUID> scannedInvoiceIds = new HashSet<>();
            for (Invoice invoice : invoiceRepository.findByClientIdAndOrgIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(clientId, orgId, range.from, range.to)) {
                if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                    countInvoiceBackfill(response, dryRun, invoice);
                }
            }
            for (Order order : ordersInBusinessPeriod) {
                invoiceRepository.findByOrderId(order.getId()).forEach(invoice -> {
                    if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                        countInvoiceBackfill(response, dryRun, invoice);
                    }
                });
            }
            for (Expense expense : expensesInBusinessPeriod) {
                invoiceRepository.findByExpenseId(expense.getId()).forEach(invoice -> {
                    if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                        countInvoiceBackfill(response, dryRun, invoice);
                    }
                });
            }
        } else {
            if (sources.contains("EXPENSE")) {
                Set<UUID> scannedInvoiceIds = new HashSet<>();
                for (Invoice invoice : invoiceRepository.findByClientIdAndOrgIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(clientId, orgId, range.from, range.to)) {
                    if (invoice.getInvoiceType() == InvoiceType.EXPENSE_RECEIPT) {
                        if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                            countInvoiceBackfill(response, dryRun, invoice);
                        }
                    }
                }
                for (Expense expense : expensesInBusinessPeriod) {
                    invoiceRepository.findByExpenseId(expense.getId()).forEach(invoice -> {
                        if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                            countInvoiceBackfill(response, dryRun, invoice);
                        }
                    });
                }
            }
            if (sources.contains("PURCHASE")) {
                Set<UUID> scannedInvoiceIds = new HashSet<>();
                for (Invoice invoice : invoiceRepository.findByClientIdAndOrgIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(clientId, orgId, range.from, range.to)) {
                    if (invoice.getInvoiceType() == InvoiceType.VENDOR_BILL) {
                        if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                            countInvoiceBackfill(response, dryRun, invoice);
                        }
                    }
                }
                for (Order order : ordersInBusinessPeriod) {
                    if (order.getOrderType() == OrderType.PURCHASE) {
                        invoiceRepository.findByOrderId(order.getId()).forEach(invoice -> {
                            if (invoice.getId() != null && scannedInvoiceIds.add(invoice.getId())) {
                                countInvoiceBackfill(response, dryRun, invoice);
                            }
                        });
                    }
                }
            }
        }
        if (sources.contains("PAYMENT")) {
            Set<UUID> scannedPaymentIds = new HashSet<>();
            for (Payment payment : paymentRepository.findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(clientId, orgId, range.from, range.to)) {
                if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                    countPaymentBackfill(response, dryRun, payment);
                }
            }
            for (Order order : ordersInBusinessPeriod) {
                paymentRepository.findByOrderId(order.getId()).forEach(payment -> {
                    if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                        countPaymentBackfill(response, dryRun, payment);
                    }
                });
            }
            for (Expense expense : expensesInBusinessPeriod) {
                paymentRepository.findByExpenseId(expense.getId()).forEach(payment -> {
                    if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                        countPaymentBackfill(response, dryRun, payment);
                    }
                });
            }
        } else {
            if (sources.contains("EXPENSE")) {
                Set<UUID> scannedPaymentIds = new HashSet<>();
                for (Payment payment : paymentRepository.findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(clientId, orgId, range.from, range.to)) {
                    if (payment.getPaymentType() == PaymentType.OUTBOUND) {
                        if (payment.getExpenseId() != null) {
                            if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                                countPaymentBackfill(response, dryRun, payment);
                            }
                        }
                    }
                }
                for (Expense expense : expensesInBusinessPeriod) {
                    paymentRepository.findByExpenseId(expense.getId()).forEach(payment -> {
                        if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                            countPaymentBackfill(response, dryRun, payment);
                        }
                    });
                }
            }
            if (sources.contains("PURCHASE")) {
                Set<UUID> scannedPaymentIds = new HashSet<>();
                for (Payment payment : paymentRepository.findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(clientId, orgId, range.from, range.to)) {
                    if (payment.getPaymentType() == PaymentType.OUTBOUND) {
                        Optional<Order> order = resolveOrder(payment.getOrderId());
                        if (order.isPresent() && order.get().getOrderType() == OrderType.PURCHASE) {
                            if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                                countPaymentBackfill(response, dryRun, payment);
                            }
                        }
                    }
                }
                for (Order order : ordersInBusinessPeriod) {
                    if (order.getOrderType() == OrderType.PURCHASE) {
                        paymentRepository.findByOrderId(order.getId()).forEach(payment -> {
                            if (payment.getId() != null && scannedPaymentIds.add(payment.getId())) {
                                countPaymentBackfill(response, dryRun, payment);
                            }
                        });
                    }
                }
            }
        }
        if (sources.contains("COGS")) {
            for (Order order : ordersInBusinessPeriod) {
                if (order.getOrderType() == OrderType.SALE) {
                    countSaleCogsBackfill(response, dryRun, order);
                }
            }
        }
        if (sources.contains("STOCK")) {
            for (StockAdjustment adjustment : stockAdjustmentRepository.findByClientIdAndOrgIdAndAdjustmentDateBetweenOrderByAdjustmentDateAsc(clientId, orgId, range.from, range.to)) {
                countOutcome(response, dryRun, "STOCK_ADJUSTMENT", adjustment.getId(), () -> postStockAdjustment(adjustment));
            }
        }

        return response;
      } catch (Exception e) {
          log.error("backfill FAILED | request={} | exception={} | message={}",
                  request, e.getClass().getName(), e.getMessage(), e);
          throw e;
      }
    }

    // Safe cleanup: rebuild only auto-posted entries and preserve manual journals.
    public AccountingBackfillResponse resyncAll() {
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        log.info("resyncAll started | clientId={} | orgId={}", clientId, orgId);

        int journalsDeleted;
        try {
            journalsDeleted = inTransaction(() -> cleanupAutoPostedAccountingData(clientId, orgId));
        } catch (DataIntegrityViolationException ex) {
            log.warn("resyncAll cleanup blocked by accounting dependencies | clientId={} | orgId={} | message={}",
                    clientId, orgId, ex.getMostSpecificCause().getMessage());
            throw new BusinessException("Unable to rebuild auto-posted accounting entries because dependent accounting records could not be cleaned. Please refresh and try again.");
        }
        inTransaction(() -> {
            recalculateAccountBalances(clientId, orgId);
            return null;
        });

        log.info("resyncAll cleanup done | journals={} | now re-running backfill", journalsDeleted);

        AccountingBackfillRequest rebuildRequest = new AccountingBackfillRequest();
        LocalDateTime yearStart = LocalDateTime.of(java.time.LocalDate.now().getYear(), 1, 1, 0, 0);
        LocalDateTime now = LocalDateTime.now();
        rebuildRequest.setFrom(yearStart);
        rebuildRequest.setTo(now);
        rebuildRequest.setSourceTypes(Set.of("INVOICE", "PAYMENT", "COGS", "STOCK", "EXPENSE", "PURCHASE"));
        rebuildRequest.setDryRun(false);

        AccountingBackfillResponse response = backfill(rebuildRequest);
        inTransaction(() -> {
            recalculateAccountBalances(clientId, orgId);
            return null;
        });
        response.setReversed(journalsDeleted);
        log.info("resyncAll complete | posted={} | skipped={} | failed={}", response.getPosted(), response.getSkipped(), response.getFailed());
        return response;
    }

    @Transactional
    public int cleanupAllAccountingData(UUID clientId, UUID orgId) {
        return cleanupAutoPostedAccountingData(clientId, orgId);
    }

    @Transactional
    public int cleanupAutoPostedAccountingData(UUID clientId, UUID orgId) {
        postingJobRepository.bulkDeleteByClientIdAndOrgId(clientId, orgId);
        partyLedgerEntryRepository.bulkDeleteForAutoPostedJournalsByClientIdAndOrgId(clientId, orgId);
        journalEntryRepository.bulkDeleteAutoPostedLinesByClientIdAndOrgId(clientId, orgId);
        int deleted = journalEntryRepository.bulkDeleteAutoPostedByClientIdAndOrgId(clientId, orgId);
        return deleted;
    }

    private void retrySource(String sourceType, UUID sourceId) {
        if (sourceType == null || sourceId == null) {
            return;
        }
        if (sourceType.endsWith("_REV")) {
            journalEntryRepository.findById(sourceId).ifPresent(original -> reverseSource(original.getSourceType(), original.getSourceId(), "Retry reversal"));
            return;
        }
        if (sourceType.endsWith("INVOICE") || "VENDOR_BILL".equals(sourceType) || "EXPENSE_RECEIPT".equals(sourceType)) {
            invoiceRepository.findById(sourceId).ifPresent(invoice -> postInvoice(resolveOrder(invoice.getOrderId()).orElse(null), invoice));
            return;
        }
        if (sourceType.endsWith("PAYMENT") || "INBOUND".equals(sourceType) || "OUTBOUND".equals(sourceType)) {
            paymentRepository.findById(sourceId).ifPresent(payment -> postPayment(resolveOrder(payment.getOrderId()).orElse(null), payment));
            return;
        }
        if ("SALE_COGS".equals(sourceType)) {
            resolveOrder(sourceId).ifPresent(this::postSaleCogs);
            return;
        }
        if ("STOCK_ADJUSTMENT".equals(sourceType)) {
            stockAdjustmentRepository.findById(sourceId).ifPresent(this::postStockAdjustment);
        }
    }

    private void retrySourceInScope(AccountingPostingJob job) {
        UUID previousOrgId = TenantContext.getCurrentOrg();
        if (job.getOrgId() != null && !Objects.equals(previousOrgId, job.getOrgId())) {
            TenantContext.setCurrentOrg(job.getOrgId());
            try {
                retrySource(job.getSourceType(), job.getSourceId());
            } finally {
                TenantContext.setCurrentOrg(previousOrgId);
            }
            return;
        }
        retrySource(job.getSourceType(), job.getSourceId());
    }

    private void countInvoiceBackfill(AccountingBackfillResponse response, boolean dryRun, Invoice invoice) {
        if (invoice.getExpenseId() != null) {
            Optional<Expense> expense = expenseRepository.findById(invoice.getExpenseId());
            countOutcome(response, dryRun, invoiceSourceType(invoice), invoice.getId(),
                    sourceDocumentDate(null, expense.orElse(null), invoice.getInvoiceDate()),
                    () -> postInvoice(null, invoice));
        } else {
            Optional<Order> order = resolveOrder(invoice.getOrderId());
            if (shouldRepairVoidedSaleInvoice(order.orElse(null), invoice)) {
                countVoidedSaleInvoiceRepair(response, dryRun, invoice);
                return;
            }
            if (shouldRepairDiscountedSaleInvoice(order.orElse(null), invoice)) {
                countDiscountedSaleInvoiceRepair(response, dryRun, order.get(), invoice);
                return;
            }
            countOutcome(response, dryRun, invoiceSourceType(invoice), invoice.getId(),
                    sourceDocumentDate(order.orElse(null), null, invoice.getInvoiceDate()),
                    () -> postInvoice(order.orElse(null), invoice));
        }
    }

    private void countPaymentBackfill(AccountingBackfillResponse response, boolean dryRun, Payment payment) {
        if (payment.getExpenseId() != null) {
            Optional<Expense> expense = expenseRepository.findById(payment.getExpenseId());
            countOutcome(response, dryRun, paymentSourceType(payment), payment.getId(),
                    sourceDocumentDate(null, expense.orElse(null), payment.getPaymentDate()),
                    () -> postPayment(null, payment));
        } else {
            Optional<Order> order = resolveOrder(payment.getOrderId());
            if (shouldRepairVoidedSalePayment(order.orElse(null), payment)) {
                countVoidedSalePaymentRepair(response, dryRun, payment);
                return;
            }
            countOutcome(response, dryRun, paymentSourceType(payment), payment.getId(),
                    sourceDocumentDate(order.orElse(null), null, payment.getPaymentDate()),
                    () -> postPayment(order.orElse(null), payment));
        }
    }

    private void countSaleCogsBackfill(AccountingBackfillResponse response, boolean dryRun, Order order) {
        if (isVoidedSaleOrder(order)) {
            countVoidedSaleCogsRepair(response, dryRun, order);
            return;
        }
        countOutcome(response, dryRun, "SALE_COGS", order.getId(), () -> postSaleCogs(order));
    }

    private void countOutcome(AccountingBackfillResponse response, boolean dryRun, String sourceType, UUID sourceId, Supplier<PostingOutcome> poster) {
        countOutcome(response, dryRun, sourceType, sourceId, null, poster);
    }

    private void countOutcome(AccountingBackfillResponse response, boolean dryRun, String sourceType, UUID sourceId,
                              LocalDateTime targetEntryDate, Supplier<PostingOutcome> poster) {
        response.setScanned(response.getScanned() + 1);
        if (alreadyPosted(sourceType, sourceId)) {
            if (!dryRun && realignAutoPostedJournalDate(sourceType, sourceId, targetEntryDate)) {
                response.setPosted(response.getPosted() + 1);
            } else {
                response.setSkipped(response.getSkipped() + 1);
            }
            return;
        }
        if (dryRun) {
            response.setPosted(response.getPosted() + 1);
            return;
        }
        PostingOutcome outcome = poster.get();
        recordOutcome(response, sourceType, sourceId, outcome);
    }

    private void countVoidedSaleInvoiceRepair(AccountingBackfillResponse response, boolean dryRun, Invoice invoice) {
        response.setScanned(response.getScanned() + 1);
        if (dryRun) {
            response.setReversed(response.getReversed() + 1);
            return;
        }
        PostingOutcome outcome = reverseInvoice(invoice, "Voided sale repaired");
        if (isActiveInvoice(invoice)) {
            invoice.setStatus("VOID");
            invoice.setDocStatus("VOIDED");
            invoice.setIsPaid(false);
            invoice.setAmountDue(BigDecimal.ZERO);
            invoiceRepository.save(invoice);
        }
        recordOutcome(response, invoiceSourceType(invoice), invoice.getId(), outcome);
    }

    private void countVoidedSalePaymentRepair(AccountingBackfillResponse response, boolean dryRun, Payment payment) {
        response.setScanned(response.getScanned() + 1);
        if (dryRun) {
            response.setReversed(response.getReversed() + 1);
            return;
        }
        PostingOutcome outcome = reversePayment(payment, "Voided sale repaired");
        payment.setDocStatus("VOIDED");
        payment.setIsactive("N");
        paymentRepository.save(payment);
        recordOutcome(response, paymentSourceType(payment), payment.getId(), outcome);
    }

    private void countVoidedSaleCogsRepair(AccountingBackfillResponse response, boolean dryRun, Order order) {
        response.setScanned(response.getScanned() + 1);
        if (dryRun) {
            response.setReversed(response.getReversed() + 1);
            return;
        }
        recordOutcome(response, "SALE_COGS", order.getId(), reverseSaleCogs(order, "Voided sale repaired"));
    }

    private void countDiscountedSaleInvoiceRepair(AccountingBackfillResponse response, boolean dryRun, Order order, Invoice invoice) {
        response.setScanned(response.getScanned() + 1);
        if (dryRun) {
            response.setPosted(response.getPosted() + 1);
            return;
        }
        recordOutcome(response, invoiceSourceType(invoice), invoice.getId(),
                replaceInvoiceJournal(order, invoice, "Discounted sale repaired"));
    }

    private void recordOutcome(AccountingBackfillResponse response, String sourceType, UUID sourceId, PostingOutcome outcome) {
        if (outcome == PostingOutcome.POSTED) {
            response.setPosted(response.getPosted() + 1);
        } else if (outcome == PostingOutcome.SKIPPED) {
            response.setSkipped(response.getSkipped() + 1);
        } else if (outcome == PostingOutcome.REVERSED) {
            response.setReversed(response.getReversed() + 1);
        } else {
            response.setFailed(response.getFailed() + 1);
            response.getFailures().add(sourceType + ":" + sourceId);
        }
    }

    private boolean shouldRepairVoidedSaleInvoice(Order order, Invoice invoice) {
        return invoice != null
                && isSaleOrder(order)
                && (isVoidedSaleOrder(order) || isVoidStatus(invoice.getStatus()) || isVoidStatus(invoice.getDocStatus()));
    }

    private boolean shouldRepairVoidedSalePayment(Order order, Payment payment) {
        return payment != null && isVoidedSaleOrder(order);
    }

    private boolean shouldRepairDiscountedSaleInvoice(Order order, Invoice invoice) {
        if (order == null || invoice == null || !isSaleOrder(order) || isVoidedSaleOrder(order)) {
            return false;
        }
        InvoiceType type = invoice.getInvoiceType() == null ? InvoiceType.CUSTOMER_INVOICE : invoice.getInvoiceType();
        if (type != InvoiceType.CUSTOMER_INVOICE || isVoidStatus(invoice.getStatus()) || isVoidStatus(invoice.getDocStatus())) {
            return false;
        }
        BigDecimal discount = effectiveInvoiceDiscount(order);
        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return findActiveSourceJournal(invoiceSourceType(invoice), invoice.getId())
                .filter(entry -> Boolean.TRUE.equals(entry.getAutoPosted()))
                .map(entry -> !invoiceJournalMatchesOrder(entry, order, invoice))
                .orElse(false);
    }

    private boolean invoiceJournalMatchesOrder(JournalEntry entry, Order order, Invoice invoice) {
        BigDecimal total = money(invoice.getTotalAmount());
        BigDecimal tax = clampTax(order != null ? order.getTotalTaxAmount() : BigDecimal.ZERO, total);
        BigDecimal discount = effectiveInvoiceDiscount(order);
        BigDecimal grossBeforeDiscount = total.subtract(tax).add(discount);

        return journalAmount(entry, AccountingDefaultsService.ACCOUNTS_RECEIVABLE, true).compareTo(total) == 0
                && journalAmount(entry, AccountingDefaultsService.DISCOUNT_ALLOWED, true).compareTo(discount) == 0
                && journalAmount(entry, AccountingDefaultsService.SALES_REVENUE, false).compareTo(grossBeforeDiscount) == 0
                && journalAmount(entry, AccountingDefaultsService.OUTPUT_TAX, false).compareTo(tax) == 0;
    }

    private BigDecimal journalAmount(JournalEntry entry, String systemKey, boolean debit) {
        if (entry == null || entry.getLines() == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        UUID clientId = requireClient();
        UUID orgId = entry.getOrgId();
        Optional<AccountingAccount> account = accountRepository.findByClientIdAndOrgIdAndSystemKeyIgnoreCase(clientId, orgId, systemKey);
        if (account.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        UUID accountId = account.get().getId();
        return entry.getLines().stream()
                .filter(line -> accountId.equals(line.getAccountId()))
                .map(line -> debit ? money(line.getDebit()) : money(line.getCredit()))
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
    }

    private boolean isVoidedSaleOrder(Order order) {
        return isSaleOrder(order)
                && (isVoidStatus(order.getOrderStatus())
                || "CANCELLED".equalsIgnoreCase(order.getOrderStatus())
                || isVoidStatus(order.getPaymentStatus()));
    }

    private boolean isSaleOrder(Order order) {
        return order != null && (order.getOrderType() == null || order.getOrderType() == OrderType.SALE);
    }

    private boolean isActiveInvoice(Invoice invoice) {
        return invoice != null
                && !"N".equalsIgnoreCase(invoice.getIsactive())
                && !isVoidStatus(invoice.getStatus())
                && !isVoidStatus(invoice.getDocStatus());
    }

    private boolean isActivePayment(Payment payment) {
        return payment != null
                && !"N".equalsIgnoreCase(payment.getIsactive())
                && !isVoidStatus(payment.getDocStatus());
    }

    private boolean isVoidStatus(String status) {
        return "VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status);
    }

    /**
     * Posts a journal entry with proper branch context.
     * @param sourceOrgId The orgId from the source entity — used to set TenantContext
     *                    so that account resolution works in "All Branches" (super admin) mode.
     */
    private PostingOutcome safePost(String sourceType, UUID sourceId, UUID sourceOrgId, Supplier<Optional<JournalEntry>> builder) {
        if (sourceId == null || sourceType == null) {
            return PostingOutcome.SKIPPED;
        }

        // When a Super Admin is in "All Branches" mode, TenantContext.orgId is null.
        // The sourceOrgId (from the order/invoice/payment entity) tells us which branch
        // this transaction belongs to. We temporarily set TenantContext so that:
        //   - AccountingDefaultsService.resolveAccount() finds branch-level system accounts
        //   - AccountingService.createJournalEntry() -> resolveOrg() gets a valid orgId
        UUID previousOrgId = TenantContext.getCurrentOrg();
        boolean contextSwitched = false;
        if (sourceOrgId != null && previousOrgId == null) {
            TenantContext.setCurrentOrg(sourceOrgId);
            contextSwitched = true;
        }

        final Exception[] originalException = new Exception[1];

        try {
            return inTransaction(() -> {
                UUID clientId = requireClient();
                UUID orgId = TenantContext.getCurrentOrg();
                AccountingPostingJob job = getOrCreatePostingJob(clientId, orgId, sourceType, sourceId);

                if (alreadyPosted(sourceType, sourceId)) {
                    markJobPosted(job, null);
                    return PostingOutcome.SKIPPED;
                }

                job.setAttemptCount(job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1);
                job.setStatus("PENDING");
                job.setLastError(null);
                job.setPostedAt(null);
                job.setPostedJournalEntryId(null);
                postingJobRepository.save(job);

                try {
                    Optional<JournalEntry> maybeEntry = builder.get();
                    if (maybeEntry.isEmpty()) {
                        markJobSkipped(job);
                        return PostingOutcome.SKIPPED;
                    }

                    JournalEntry saved = accountingService.createJournalEntry(maybeEntry.get());
                    markJobPosted(job, saved.getId());
                    return sourceType.endsWith("_REV") ? PostingOutcome.REVERSED : PostingOutcome.POSTED;
                } catch (Exception ex) {
                    log.warn("Accounting posting failed | sourceType={} | sourceId={} | message={}", sourceType, sourceId, ex.getMessage());
                    originalException[0] = ex;
                    markJobFailed(job, ex);
                    throw ex instanceof RuntimeException ? (RuntimeException) ex : new RuntimeException(ex);
                }
            });
        } catch (Exception ex) {
            log.error("Fatal transaction error during accounting posting | sourceType={} | sourceId={}", sourceType, sourceId, ex);

            final Exception exToSave = originalException[0] != null ? originalException[0] : ex;

            try {
                inTransaction(() -> {
                    UUID clientId = requireClient();
                    UUID orgId = TenantContext.getCurrentOrg();
                    AccountingPostingJob job = getOrCreatePostingJob(clientId, orgId, sourceType, sourceId);
                    markJobFailed(job, exToSave);
                    return null;
                });
            } catch (Exception writeEx) {
                log.error("Failed to write failure status to posting job", writeEx);
            }
            return PostingOutcome.FAILED;
        } finally {
            if (contextSwitched) {
                TenantContext.setCurrentOrg(previousOrgId);
            }
        }
    }

    private PostingOutcome safeReplaceAutoPosted(String sourceType, UUID sourceId, UUID sourceOrgId, String reason,
                                                 Supplier<Optional<JournalEntry>> builder) {
        if (sourceId == null || sourceType == null) {
            return PostingOutcome.SKIPPED;
        }

        UUID previousOrgId = TenantContext.getCurrentOrg();
        boolean contextSwitched = false;
        if (sourceOrgId != null && !Objects.equals(previousOrgId, sourceOrgId)) {
            TenantContext.setCurrentOrg(sourceOrgId);
            contextSwitched = true;
        }
        try {
            return inTransaction(() -> {
                UUID clientId = requireClient();
                UUID orgId = TenantContext.getCurrentOrg();
                Optional<JournalEntry> existing = findActiveSourceJournal(sourceType, sourceId);
                if (existing.isPresent()) {
                    JournalEntry oldEntry = existing.get();
                    if (!Boolean.TRUE.equals(oldEntry.getAutoPosted())) {
                        return PostingOutcome.SKIPPED;
                    }
                    oldEntry.setStatus(JournalStatus.VOID);
                    oldEntry.setIsactive("N");
                    oldEntry.setDescription(appendSystemNote(oldEntry.getDescription(), "Replaced: " + nullToDash(reason)));
                    journalEntryRepository.saveAndFlush(oldEntry);
                }

                Optional<JournalEntry> maybeEntry = builder.get();
                if (maybeEntry.isEmpty()) {
                    return PostingOutcome.SKIPPED;
                }
                JournalEntry saved = accountingService.createJournalEntry(maybeEntry.get());
                markJobPosted(clientId, orgId, sourceType, sourceId, saved.getId());
                return PostingOutcome.POSTED;
            });
        } catch (Exception ex) {
            log.error("Fatal transaction error during accounting repost | sourceType={} | sourceId={}", sourceType, sourceId, ex);
            return PostingOutcome.FAILED;
        } finally {
            if (contextSwitched) {
                TenantContext.setCurrentOrg(previousOrgId);
            }
        }
    }

    private PostingOutcome postAfterCommitOrNow(String sourceType, UUID sourceId, UUID sourceOrgId, Supplier<Optional<JournalEntry>> builder) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            UUID clientId = TenantContext.getCurrentTenant();
            UUID orgId = TenantContext.getCurrentOrg();
            // Capture the effective orgId: prefer sourceOrgId, then TenantContext
            UUID effectiveOrgId = sourceOrgId != null ? sourceOrgId : orgId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    UUID previousClientId = TenantContext.getCurrentTenant();
                    UUID previousOrgId = TenantContext.getCurrentOrg();
                    TenantContext.setCurrentTenant(clientId);
                    TenantContext.setCurrentOrg(effectiveOrgId);
                    try {
                        safePost(sourceType, sourceId, sourceOrgId, builder);
                    } catch (Exception ex) {
                        log.warn("Deferred accounting posting failed | sourceType={} | sourceId={} | message={}",
                                sourceType, sourceId, ex.getMessage());
                    } finally {
                        TenantContext.setCurrentTenant(previousClientId);
                        TenantContext.setCurrentOrg(previousOrgId);
                    }
                }
            });
            return PostingOutcome.SKIPPED;
        }
        return safePost(sourceType, sourceId, sourceOrgId, builder);
    }

    private PostingOutcome replaceAfterCommitOrNow(String sourceType, UUID sourceId, UUID sourceOrgId, String reason,
                                                   Supplier<Optional<JournalEntry>> builder) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            UUID clientId = TenantContext.getCurrentTenant();
            UUID orgId = TenantContext.getCurrentOrg();
            UUID effectiveOrgId = sourceOrgId != null ? sourceOrgId : orgId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    UUID previousClientId = TenantContext.getCurrentTenant();
                    UUID previousOrgId = TenantContext.getCurrentOrg();
                    TenantContext.setCurrentTenant(clientId);
                    TenantContext.setCurrentOrg(effectiveOrgId);
                    try {
                        safeReplaceAutoPosted(sourceType, sourceId, effectiveOrgId, reason, builder);
                    } catch (Exception ex) {
                        log.warn("Deferred accounting repost failed | sourceType={} | sourceId={} | message={}",
                                sourceType, sourceId, ex.getMessage());
                    } finally {
                        TenantContext.setCurrentTenant(previousClientId);
                        TenantContext.setCurrentOrg(previousOrgId);
                    }
                }
            });
            return PostingOutcome.SKIPPED;
        }
        return safeReplaceAutoPosted(sourceType, sourceId, sourceOrgId, reason, builder);
    }

    private Optional<JournalEntry> buildInvoiceJournal(Order order, Expense expense, Invoice invoice) {
        BigDecimal total = money(invoice.getTotalAmount());
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        InvoiceType type = invoice.getInvoiceType() == null ? InvoiceType.CUSTOMER_INVOICE : invoice.getInvoiceType();
        BigDecimal tax = clampTax(order != null ? order.getTotalTaxAmount() : BigDecimal.ZERO, total);
        BigDecimal base = total.subtract(tax);

        List<JournalLine> lines = new ArrayList<>();
        if (type == InvoiceType.CUSTOMER_INVOICE) {
            BigDecimal discount = effectiveInvoiceDiscount(order);
            BigDecimal grossBeforeDiscount = base.add(discount);
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_RECEIVABLE), total, BigDecimal.ZERO,
                    PartyType.CUSTOMER, invoice.getCustomerId(), "Customer invoice receivable");
            if (discount.compareTo(BigDecimal.ZERO) > 0) {
                addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.DISCOUNT_ALLOWED), discount, BigDecimal.ZERO,
                        null, null, "Sales discount allowed");
            }
            if (grossBeforeDiscount.compareTo(BigDecimal.ZERO) > 0) {
                addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.SALES_REVENUE), BigDecimal.ZERO, grossBeforeDiscount,
                        null, null, "Gross sales revenue");
            }
            if (tax.compareTo(BigDecimal.ZERO) > 0) {
                addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.OUTPUT_TAX), BigDecimal.ZERO, tax,
                        null, null, "Output tax");
            }
        } else if (type == InvoiceType.VENDOR_BILL) {
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INVENTORY_ASSET), base, BigDecimal.ZERO,
                    null, null, "Purchase inventory");
            if (tax.compareTo(BigDecimal.ZERO) > 0) {
                addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INPUT_TAX), tax, BigDecimal.ZERO,
                        null, null, "Input tax");
            }
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_PAYABLE), BigDecimal.ZERO, total,
                    PartyType.VENDOR, invoice.getVendorId(), "Vendor payable");
        } else {
            AccountingAccount expenseAccount = resolveExpenseAccount(invoice, order, expense);
            addLine(lines, expenseAccount, base, BigDecimal.ZERO, null, null, "Expense");
            if (tax.compareTo(BigDecimal.ZERO) > 0) {
                addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INPUT_TAX), tax, BigDecimal.ZERO,
                        null, null, "Input tax");
            }
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_PAYABLE), BigDecimal.ZERO, total,
                    null, null, "Expense payable");
        }

        UUID orgId = invoice.getOrgId() != null ? invoice.getOrgId() : (order != null ? order.getOrgId() : (expense != null ? expense.getOrgId() : null));
        UUID currencyId = order != null ? order.getCurrencyId() : (expense != null ? expense.getCurrencyId() : null);
        LocalDateTime docDate = sourceDocumentDate(order, expense, invoice.getInvoiceDate());
        return journal(invoiceSourceType(invoice), invoice.getId(), docDate, invoice.getTerminalId(),
                order != null ? order.getWarehouseId() : null, currencyId,
                orgId, "Auto posted invoice " + invoice.getInvoiceNo(), lines);
    }

    private Optional<JournalEntry> buildPaymentJournal(Order order, Expense expense, Payment payment) {
        BigDecimal amount = money(payment.getAmountPaid());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        boolean inbound = payment.getPaymentType() == null || payment.getPaymentType() == PaymentType.INBOUND;
        List<JournalLine> lines = new ArrayList<>();
        List<PaymentSplit> splits = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());
        if (splits.isEmpty()) {
            splits = List.of(PaymentSplit.builder()
                    .paymentId(payment.getId())
                    .paymentMethod(payment.getPaymentMethod())
                    .amount(amount)
                    .build());
        }

        for (PaymentSplit split : splits) {
            BigDecimal splitAmount = money(split.getAmount());
            if (splitAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            AccountingAccount paymentAccount = defaultsService.resolvePaymentAccount(split.getPaymentMethod());
            if (inbound) {
                addLine(lines, paymentAccount, splitAmount, BigDecimal.ZERO, null, null, "Payment received");
            } else {
                addLine(lines, paymentAccount, BigDecimal.ZERO, splitAmount, null, null, "Payment made");
            }
        }

        if (inbound) {
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_RECEIVABLE), BigDecimal.ZERO, amount,
                    PartyType.CUSTOMER, resolvePaymentCustomerId(order, payment), "Receivable settled");
        } else {
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.ACCOUNTS_PAYABLE), amount, BigDecimal.ZERO,
                    PartyType.VENDOR, order != null ? order.getVendorId() : null, "Payable settled");
        }

        UUID orgId = payment.getOrgId() != null ? payment.getOrgId() : (order != null ? order.getOrgId() : (expense != null ? expense.getOrgId() : null));
        UUID currencyId = order != null ? order.getCurrencyId() : (expense != null ? expense.getCurrencyId() : null);
        LocalDateTime docDate = sourceDocumentDate(order, expense, payment.getPaymentDate());
        return journal(paymentSourceType(payment), payment.getId(), docDate, payment.getTerminalId(),
                order != null ? order.getWarehouseId() : null, currencyId,
                orgId, "Auto posted payment " + payment.getReferenceNo(), lines);
    }

    private Optional<JournalEntry> buildSaleCogsJournal(Order order) {
        BigDecimal costTotal = BigDecimal.ZERO;
        if (order.getLines() != null) {
            for (OrderLine line : order.getLines()) {
                if (line == null || !line.isActive() || line.getProductId() == null) {
                    continue;
                }
                BigDecimal unitCost = resolveUnitCost(line);
                costTotal = costTotal.add(unitCost.multiply(quantity(line.getQuantity())));
            }
        }
        costTotal = money(costTotal);
        if (costTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        List<JournalLine> lines = new ArrayList<>();
        addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.PURCHASE_COGS), costTotal, BigDecimal.ZERO,
                null, null, "Sales cost of goods sold");
        addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INVENTORY_ASSET), BigDecimal.ZERO, costTotal,
                null, null, "Inventory consumed by sale");
        return journal("SALE_COGS", order.getId(), orderDate(order), order.getTerminalId(), order.getWarehouseId(), order.getCurrencyId(),
                order.getOrgId(), "Auto posted COGS for " + order.getOrderNo(), lines);
    }

    private Optional<JournalEntry> buildStockAdjustmentJournal(StockAdjustment adjustment) {
        BigDecimal value = BigDecimal.ZERO;
        if (adjustment.getLines() != null) {
            for (StockAdjustmentLine line : adjustment.getLines()) {
                if (line == null || !"Y".equalsIgnoreCase(line.getIsActive())) {

                    continue;
                }
                value = value.add(quantity(line.getQuantityChange()).multiply(money(line.getUnitCost())));
            }
        }
        value = money(value);
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }
        List<JournalLine> lines = new ArrayList<>();
        BigDecimal absolute = value.abs();
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INVENTORY_ASSET), absolute, BigDecimal.ZERO,
                    null, null, "Stock adjustment increase");
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.STOCK_ADJUSTMENT_GAIN_LOSS), BigDecimal.ZERO, absolute,
                    null, null, "Stock adjustment gain");
        } else {
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.STOCK_ADJUSTMENT_GAIN_LOSS), absolute, BigDecimal.ZERO,
                    null, null, "Stock adjustment loss");
            addLine(lines, defaultsService.resolveAccount(AccountingDefaultsService.INVENTORY_ASSET), BigDecimal.ZERO, absolute,
                    null, null, "Stock adjustment decrease");
        }
        return journal("STOCK_ADJUSTMENT", adjustment.getId(), adjustment.getAdjustmentDate(), null, adjustment.getWarehouseId(), null,
                adjustment.getOrgId(), "Auto posted stock adjustment " + adjustment.getAdjustmentNumber(), lines);
    }

    private Optional<JournalEntry> buildReversalJournal(JournalEntry original, String reason) {
        List<JournalLine> lines = new ArrayList<>();
        for (JournalLine originalLine : original.getLines()) {
            addLine(lines,
                    accountById(originalLine.getAccountId()),
                    money(originalLine.getCredit()),
                    money(originalLine.getDebit()),
                    originalLine.getPartyType(),
                    originalLine.getPartyId(),
                    "Reversal: " + nullToDash(originalLine.getDescription()));
        }
        return journal(original.getSourceType() + "_REV", original.getId(), LocalDateTime.now(), original.getTerminalId(),
                original.getWarehouseId(), original.getCurrencyId(),
                original.getOrgId(), "Reversal for " + original.getEntryNo() + (reason == null || reason.isBlank() ? "" : " - " + reason),
                lines).map(entry -> {
                    entry.setReversalOfJournalEntryId(original.getId());
                    return entry;
                });
    }

    /**
     * Builds a JournalEntry from the given parameters.
     * The orgId MUST be derived from the source entity (order, invoice, payment, etc.)
     * — never from TenantContext — so that journal posting works correctly even when
     * the user is a Super Admin in "All Branches" mode (where TenantContext.orgId is null).
     */
    private Optional<JournalEntry> journal(String sourceType, UUID sourceId, LocalDateTime date, UUID terminalId, UUID warehouseId,
                                           UUID currencyId, UUID orgId, String description, List<JournalLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry entry = JournalEntry.builder()
                .entryNo("AUTO-" + sourceType + "-" + sourceId.toString().substring(0, 8).toUpperCase(Locale.ROOT))
                .entryDate(date != null ? date : LocalDateTime.now())
                .status(JournalStatus.POSTED)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .terminalId(terminalId)
                .warehouseId(warehouseId)
                .currencyId(currencyId)
                .autoPosted(true)
                .description(description)
                .lines(new ArrayList<>())
                .build();
        entry.setOrgId(orgId);
        lines.forEach(entry::attachLine);
        return Optional.of(entry);
    }

    private BigDecimal effectiveInvoiceDiscount(Order order) {
        if (order == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (order.getLines() == null || order.getLines().isEmpty()) {
            return money(order.getTotalDiscountAmount()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalExTaxDiscount = BigDecimal.ZERO;
        for (com.restaurant.pos.order.domain.OrderLine line : order.getLines()) {
            if (line.getGrossLineAmount() == null || line.getTaxType() == null) {
                BigDecimal disc = money(line.getDiscountAmount());
                BigDecimal taxRate = money(line.getTaxRate());
                boolean isInclusive = line.getIsPackagedGood() != null && line.getIsPackagedGood();
                BigDecimal discExTax = isInclusive 
                        ? disc.divide(BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP)
                        : disc;
                totalExTaxDiscount = totalExTaxDiscount.add(discExTax);
                continue;
            }
            BigDecimal grossLine = money(line.getGrossLineAmount());
            BigDecimal taxable = money(line.getTaxableAmount());
            BigDecimal taxRate = money(line.getTaxRate());
            boolean isInclusive = "INCLUSIVE".equalsIgnoreCase(String.valueOf(line.getTaxType()));
            
            BigDecimal lineGrossExTax = isInclusive 
                    ? grossLine.divide(BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP)
                    : grossLine;
            
            BigDecimal lineDiscountExTax = lineGrossExTax.subtract(taxable).max(BigDecimal.ZERO);
            totalExTaxDiscount = totalExTaxDiscount.add(lineDiscountExTax);
        }
        return totalExTaxDiscount.setScale(2, RoundingMode.HALF_UP);
    }

    private void addLine(List<JournalLine> lines, AccountingAccount account, BigDecimal debit, BigDecimal credit,
                         PartyType partyType, UUID partyId, String description) {
        BigDecimal debitAmount = money(debit);
        BigDecimal creditAmount = money(credit);
        if (debitAmount.compareTo(BigDecimal.ZERO) == 0 && creditAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        lines.add(JournalLine.builder()
                .accountId(account.getId())
                .debit(debitAmount)
                .credit(creditAmount)
                .partyType(partyType)
                .partyId(partyId)
                .description(description)
                .build());
    }

    private AccountingAccount resolveExpenseAccount(Invoice invoice, Order order, Expense expense) {
        UUID categoryId = null;
        if (expense != null) {
            categoryId = expense.getCategoryId();
        } else if (invoice != null && invoice.getExpenseId() != null) {
            categoryId = expenseRepository.findById(invoice.getExpenseId()).map(Expense::getCategoryId).orElse(null);
        }
        if (categoryId != null) {
            Optional<ExpenseCategory> category = expenseCategoryRepository.findById(categoryId);
            if (category.isPresent() && category.get().getAccountId() != null) {
                UUID clientId = requireClient();
                UUID orgId = TenantContext.getCurrentOrg();
                return accountRepository.findByIdAndClientIdAndOrgId(category.get().getAccountId(), clientId, orgId)
                        .orElseGet(() -> defaultsService.resolveAccount(AccountingDefaultsService.OPERATING_EXPENSES));
            }
        }
        return defaultsService.resolveAccount(AccountingDefaultsService.OPERATING_EXPENSES);
    }

    private AccountingAccount accountById(UUID accountId) {
        return accountRepository.findByIdAndClientIdAndOrgId(accountId, requireClient(), TenantContext.getCurrentOrg())
                .orElseThrow(() -> new BusinessException("Accounting account is unavailable for reversal"));
    }

    private Optional<Order> resolveOrder(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orderRepository.findByIdWithLines(orderId);
    }

    private UUID resolvePaymentCustomerId(Order order, Payment payment) {
        if (order != null && order.getCustomerId() != null) {
            return order.getCustomerId();
        }
        if (payment != null && payment.getCustomerId() != null) {
            return payment.getCustomerId();
        }
        if (payment != null && payment.getInvoiceId() != null) {
            return invoiceRepository.findById(payment.getInvoiceId())
                    .map(Invoice::getCustomerId)
                    .orElse(null);
        }
        return null;
    }
    private boolean alreadyPosted(String sourceType, UUID sourceId) {
        if (sourceType == null || sourceId == null) {
            return false;
        }
        return findActiveSourceJournal(sourceType, sourceId).isPresent();
    }

    private Optional<JournalEntry> findActiveSourceJournal(String sourceType, UUID sourceId) {
        if (sourceType == null || sourceId == null) {
            return Optional.empty();
        }
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        return journalEntryRepository.findActiveBySourceWithLines(clientId, orgId, sourceType, sourceId, JournalStatus.POSTED)
                .stream()
                .findFirst();
    }

    private boolean realignAutoPostedJournalDate(String sourceType, UUID sourceId, LocalDateTime targetEntryDate) {
        if (sourceType == null || sourceId == null || targetEntryDate == null) {
            return false;
        }
        Optional<JournalEntry> existing = findActiveSourceJournal(sourceType, sourceId);
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.get().getAutoPosted())) {
            return false;
        }
        JournalEntry entry = existing.get();
        if (entry.getEntryDate() != null && entry.getEntryDate().equals(targetEntryDate)) {
            return false;
        }
        entry.setEntryDate(targetEntryDate);
        journalEntryRepository.save(entry);
        return true;
    }

    private String appendSystemNote(String description, String note) {
        if (description == null || description.isBlank()) {
            return note;
        }
        return description + " | " + note;
    }

    /** Find posting job, falling back to org-agnostic query when orgId is null */
    private Optional<AccountingPostingJob> findPostingJob(UUID clientId, UUID orgId, String sourceType, UUID sourceId) {
        if (orgId != null) {
            return postingJobRepository.findByClientIdAndOrgIdAndSourceTypeAndSourceId(clientId, orgId, sourceType, sourceId);
        }
        return postingJobRepository.findFirstByClientIdAndSourceTypeAndSourceId(clientId, sourceType, sourceId);
    }

    private AccountingPostingJob getOrCreatePostingJob(UUID clientId, UUID orgId, String sourceType, UUID sourceId) {
        List<AccountingPostingJob> locked = postingJobRepository.findLockedBySource(clientId, orgId, sourceType, sourceId);
        if (!locked.isEmpty()) {
            return locked.get(0);
        }
        postingJobRepository.insertIfAbsent(UUID.randomUUID(), clientId, orgId, sourceType, sourceId, "PENDING");
        locked = postingJobRepository.findLockedBySource(clientId, orgId, sourceType, sourceId);
        if (!locked.isEmpty()) {
            return locked.get(0);
        }
        throw new BusinessException("Unable to reserve accounting posting job");
    }

    private void markJobPosted(UUID clientId, UUID orgId, String sourceType, UUID sourceId, UUID journalEntryId) {
        markJobPosted(getOrCreatePostingJob(clientId, orgId, sourceType, sourceId), journalEntryId);
    }

    private void markJobPosted(AccountingPostingJob job, UUID journalEntryId) {
        job.setStatus("POSTED");
        job.setLastError(null);
        job.setPostedAt(LocalDateTime.now());
        if (journalEntryId != null) {
            job.setPostedJournalEntryId(journalEntryId);
        }
        postingJobRepository.save(job);
    }

    private void markJobSkipped(AccountingPostingJob job) {
        job.setStatus("SKIPPED");
        job.setLastError(null);
        postingJobRepository.save(job);
    }

    private void markJobFailed(AccountingPostingJob job, Exception ex) {
        job.setStatus("FAILED");
        job.setLastError(getCleanErrorMessage(ex));
        postingJobRepository.save(job);
    }

    private String getCleanErrorMessage(Exception ex) {
        if (ex == null) {
            return "Unknown error";
        }
        if (ex instanceof org.springframework.dao.DataAccessException) {
            Throwable specificCause = ((org.springframework.dao.DataAccessException) ex).getMostSpecificCause();
            if (specificCause != null && specificCause.getMessage() != null) {
                return specificCause.getMessage();
            }
        }
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : ex.getMessage();
    }

    private String invoiceSourceType(Invoice invoice) {
        InvoiceType type = invoice.getInvoiceType() == null ? InvoiceType.CUSTOMER_INVOICE : invoice.getInvoiceType();
        return type.name();
    }

    private String paymentSourceType(Payment payment) {
        PaymentType type = payment.getPaymentType() == null ? PaymentType.INBOUND : payment.getPaymentType();
        return type == PaymentType.INBOUND ? "INBOUND_PAYMENT" : "OUTBOUND_PAYMENT";
    }

    private BigDecimal resolveUnitCost(OrderLine line) {
        return productRepository.findById(line.getProductId())
                .map(Product::getCostPrice)
                .map(this::money)
                .orElse(BigDecimal.ZERO);
    }

    private LocalDateTime orderDate(Order order) {
        Instant instant = order.getOrderDate() != null ? order.getOrderDate() : Instant.now();
        ZoneId zoneId = timezoneResolver.resolveTimezone(order.getClientId(), order.getOrgId());
        return LocalDateTime.ofInstant(instant, zoneId);
    }

    private LocalDateTime sourceDocumentDate(Order order, Expense expense, LocalDateTime fallbackDate) {
        if (order != null && order.getOrderDate() != null) {
            return orderDate(order);
        }
        if (expense != null && expense.getExpenseDate() != null) {
            ZoneId zoneId = timezoneResolver.resolveTimezone(expense.getClientId(), expense.getOrgId());
            return LocalDateTime.ofInstant(expense.getExpenseDate(), zoneId);
        }
        return fallbackDate != null ? fallbackDate : LocalDateTime.now();
    }

    private BigDecimal clampTax(BigDecimal tax, BigDecimal total) {
        BigDecimal value = money(tax);
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(total) > 0) {
            return total;
        }
        return value;
    }

    private LocalDateTime parseBackfillDateTime(String value) {
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
            throw new BusinessException("Invalid accounting backfill date-time: " + value);
        }
    }

    private DateRange boundedRange(LocalDateTime from, LocalDateTime to) {
        LocalDateTime resolvedTo = to != null ? to : LocalDateTime.now();
        LocalDateTime resolvedFrom = from != null ? from : resolvedTo.minusDays(31);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new BusinessException("From date must be before to date");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_BACKFILL_DAYS) {
            throw new BusinessException("Accounting backfill range cannot exceed " + MAX_BACKFILL_DAYS + " days");
        }
        return new DateRange(resolvedFrom, resolvedTo);
    }

    private Set<String> normalizeSourceTypes(Set<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return new LinkedHashSet<>(List.of("INVOICE", "PAYMENT", "COGS", "STOCK"));
        }
        Set<String> normalized = new LinkedHashSet<>();
        sourceTypes.forEach(type -> {
            if (type != null && !type.isBlank()) {
                String source = type.trim().toUpperCase(Locale.ROOT);
                switch (source) {
                    case "CUSTOMER_INVOICE", "VENDOR_BILL", "EXPENSE_RECEIPT", "INVOICES", "INVOICE" ->
                            normalized.add("INVOICE");
                    case "INBOUND_PAYMENT", "OUTBOUND_PAYMENT", "PAYMENTS", "PAYMENT" ->
                            normalized.add("PAYMENT");
                    case "SALE_COGS", "COGS" -> normalized.add("COGS");
                    case "STOCK_ADJUSTMENT", "STOCK_ADJUSTMENTS", "STOCK" -> normalized.add("STOCK");
                    case "EXPENSE", "EXPENSES" -> normalized.add("EXPENSE");
                    case "PURCHASE", "PURCHASES", "PURCHASE_BILL", "PURCHASE_PAYMENT" -> normalized.add("PURCHASE");
                    default -> normalized.add(source);
                }
            }
        });
        return normalized.isEmpty() ? new LinkedHashSet<>(List.of("INVOICE", "PAYMENT", "COGS", "STOCK")) : normalized;
    }

    @Transactional
    public void recalculateAccountBalances(UUID clientId, UUID orgId) {
        List<AccountingAccount> accounts = orgId == null
                ? accountRepository.findByClientIdOrderByCodeAsc(clientId)
                : accountRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId);
        Map<UUID, AccountingAccount> accountsById = new LinkedHashMap<>();
        for (AccountingAccount account : accounts) {
            account.setCurrentBalance(money(account.getOpeningBalance()));
            accountsById.put(account.getId(), account);
        }

        journalEntryRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("status"), JournalStatus.POSTED));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }).forEach(entry -> {
            if (entry.getLines() == null) {
                return;
            }
            for (JournalLine line : entry.getLines()) {
                AccountingAccount account = accountsById.get(line.getAccountId());
                if (account != null) {
                    account.setCurrentBalance(money(account.getCurrentBalance()).add(accountBalanceEffect(account, money(line.getDebit()), money(line.getCredit()))));
                }
            }
        });
        accountRepository.saveAll(accountsById.values());
    }

    private BigDecimal accountBalanceEffect(AccountingAccount account, BigDecimal debit, BigDecimal credit) {
        boolean debitNormal = account.getAccountType() == AccountType.ASSET || account.getAccountType() == AccountType.EXPENSE;
        return debitNormal ? debit.subtract(credit) : credit.subtract(debit);
    }

    private <T> T inTransaction(Supplier<T> action) {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private UUID requireClient() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        return clientId;
    }

    private boolean isVoid(String status) {
        return status != null && ("VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status));
    }

    private boolean isInactive(String isactive) {
        return "N".equalsIgnoreCase(isactive);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public enum PostingOutcome {
        POSTED,
        SKIPPED,
        REVERSED,
        FAILED
    }

    private static final class DateRange {
        private final LocalDateTime from;
        private final LocalDateTime to;

        private DateRange(LocalDateTime from, LocalDateTime to) {
            this.from = from;
            this.to = to;
        }
    }
}
