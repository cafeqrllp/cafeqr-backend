package com.restaurant.pos.expense.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.expense.exception.ExpenseAlreadyVoidedException;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.category.service.ExpenseCategoryPolicy;
import com.restaurant.pos.common.context.ContextProvider;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.domain.ExpensePaymentMethod;
import com.restaurant.pos.expense.domain.ExpenseStatus;
import com.restaurant.pos.category.domain.ExpenseCategory;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.expense.dto.CreateExpenseRequest;
import com.restaurant.pos.expense.dto.ExpenseBaseRequest;
import com.restaurant.pos.expense.dto.UpdateExpenseRequest;
import com.restaurant.pos.expense.dto.ExpenseResponse;
import com.restaurant.pos.expense.dto.VoidExpenseResponse;
import com.restaurant.pos.expense.dto.ExpenseSearchCriteria;
import com.restaurant.pos.expense.idempotency.IdempotencyStore;
import com.restaurant.pos.expense.mapper.ExpenseMapper;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import com.restaurant.pos.expense.spec.ExpenseSpecification;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.domain.InvoiceLine;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.invoice.service.InvoiceService;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.validation.annotation.Validated;

/**
 * Service for managing expense transactions.
 * Fully utilizing the dedicated Expense domain entity and repository
 */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class ExpenseService {

    private static final int EXPENSE_WRITE_TIMEOUT_SECONDS = 30;

    private final ExpenseCategoryRepository categoryRepository;
    private final ExpenseRepository expenseRepository;
    private final ExpenseMapper expenseMapper;
    private final DocumentSequenceService sequenceService;
    private final AccountingPostingService accountingPostingService;
    private final CurrencyRepository currencyRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final InvoiceService invoiceService;

    private final IdempotencyStore idempotencyStore;
    private final ContextProvider contextProvider;
    private final ExpenseCategoryPolicy categoryPolicy;
    private final com.restaurant.pos.order.repository.PaymentSplitRepository paymentSplitRepository;

    // ─────────────────────────────────────────────────────────────
    // Sort Field Translation
    // ─────────────────────────────────────────────────────────────

    /**
     * Maps logical API sort fields to internal entity properties.
     * Only fields that sort meaningfully are exposed.
     */
    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "expenseDate", "expenseDate",
            "orderDate", "expenseDate",
            "amount", "amount");

    @NonNull
    private Pageable translatePageable(@NonNull Pageable pageable) {
        List<Sort.Order> translated = pageable.getSort().stream()
                .map(order -> {
                    String mapped = SORT_FIELD_MAP.getOrDefault(order.getProperty(), order.getProperty());
                    return order.withProperty(mapped);
                })
                .toList();
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(translated));
    }

    // ─────────────────────────────────────────────────────────────
    // CRUD Operations
    // ─────────────────────────────────────────────────────────────

    /**
     * Records a new expense transaction with idempotency protection.
     */
    @Transactional(timeout = EXPENSE_WRITE_TIMEOUT_SECONDS, rollbackFor = Exception.class)
    public ExpenseResponse createExpense(String idempotencyKey, CreateExpenseRequest request) {
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        ExpenseWriteScope targetScope = resolveExpenseWriteScope(request);

        // Idempotency check — prevents duplicate Expense+Invoice+Payment triplets on retries
        String cacheKey = String.format("tenant=%s:org=%s:key=%s", 
                contextProvider.getCurrentTenant(), 
                targetScope.cacheKeyPart(), 
                idempotencyKey);
        ExpenseResponse cached = idempotencyStore.get(cacheKey);
        if (cached != null) {
            log.info("Expense idempotency hit | keyPresent={}", hasIdempotencyKey);
            return cached;
        }

        long startedAtNanos = System.nanoTime();
        ZoneId zoneId = contextProvider.getCurrentTimezone();

        try {
            ExpenseCategory category = getOwnedExpenseCategory(request.getCategoryId(), targetScope.orgId());

            if (!category.isActive()) {
                throw new BusinessException("Cannot assign expense to an inactive category");
            }

            Expense expense = buildExpenseEntity(request, category, targetScope);

            // Generate unique sequence number for the expense
            String expenseNo = sequenceService.generateNextSequence(DocumentType.EXPENSE, targetScope.orgId());
            expense.setExpenseNo(expenseNo);

            log.info(
                    "Creating expense | categoryId={} | branchId={} | amount={} | paymentMethod={} | keyPresent={} | timeoutSeconds={}",
                    request.getCategoryId(),
                    request.getBranchId(),
                    request.getAmount(),
                    request.getPaymentMethod(),
                    hasIdempotencyKey,
                    EXPENSE_WRITE_TIMEOUT_SECONDS);

            // Save the standalone Expense entity
            Expense savedExpense = expenseRepository.save(expense);

            log.info(
                    "Expense created | expenseId={} | expenseNo={} | categoryId={} | branchId={} | keyPresent={}",
                    savedExpense.getId(),
                    savedExpense.getExpenseNo(),
                    category.getId(),
                    savedExpense.getOrgId(),
                    hasIdempotencyKey);

            // Construct and save the associated Invoice & Payment (timezone pre-resolved)
            Invoice savedInvoice = createExpenseInvoice(savedExpense, category, targetScope, zoneId);
            Payment savedPayment = createExpensePayment(savedExpense, savedInvoice, category, targetScope, zoneId, request.getCashAmount(), request.getOnlineAmount());

            // Post to Accounting Posting Service (null for order)
            postAccountingEntries(savedInvoice, savedPayment);

            String updaterName = resolveUserDisplayName(savedExpense.getUpdatedBy());
            ExpenseResponse response = populateSplits(expenseMapper.toExpenseResponse(savedExpense, category.getName(), updaterName), savedExpense.getId());

            idempotencyStore.put(cacheKey, response);

            log.info(
                    "Expense created response | expenseId={} | referenceNumber={} | categoryId={} | branchId={} | amount={} | elapsedMs={} | keyPresent={}",
                    savedExpense.getId(),
                    response.getReferenceNumber(),
                    category.getId(),
                    savedExpense.getOrgId(),
                    response.getAmount(),
                    elapsedMillis(startedAtNanos),
                    hasIdempotencyKey);

            return response;
        } catch (RuntimeException ex) {
            log.error(
                    "Expense creation failed | categoryId={} | branchId={} | amount={} | paymentMethod={} | elapsedMs={} | keyPresent={}",
                    request.getCategoryId(),
                    request.getBranchId(),
                    request.getAmount(),
                    request.getPaymentMethod(),
                    elapsedMillis(startedAtNanos),
                    hasIdempotencyKey,
                    ex);
            throw ex;
        }
    }

    private Invoice createExpenseInvoice(Expense savedExpense, ExpenseCategory category,
            ExpenseWriteScope targetScope, ZoneId zoneId) {
        String invoiceNo = sequenceService.generateNextSequence(DocumentType.EXPENSE_RECEIPT, targetScope.orgId());
        Invoice invoice = Invoice.builder()
                .invoiceType(InvoiceType.EXPENSE_RECEIPT)
                .terminalId(savedExpense.getTerminalId())
                .expenseId(savedExpense.getId())
                .invoiceNo(invoiceNo)
                .invoiceDate(toLocalDateTime(savedExpense.getExpenseDate(), zoneId))
                .totalAmount(savedExpense.getAmount())
                .amountDue(BigDecimal.ZERO) // PAID immediately
                .status("PAID")
                .docStatus("COMPLETED")
                .isPaid(true)
                .isCredit(false)
                .description(savedExpense.getDescription())
                .isactive("Y")
                .build();
        invoice.setClientId(savedExpense.getClientId());
        invoice.setOrgId(savedExpense.getOrgId());
        invoice.setCreatedBy(savedExpense.getCreatedBy());
        invoice.setUpdatedBy(savedExpense.getUpdatedBy());

        InvoiceLine il = InvoiceLine.builder()
                .quantity(BigDecimal.ONE)
                .unitPrice(savedExpense.getAmount())
                .lineTotal(savedExpense.getAmount())
                .productName(category != null ? category.getName() : "Expense")
                .isactive("Y")
                .build();
        invoice.addLine(il);

        return invoiceService.createInvoice(invoice);
    }

    private Payment createExpensePayment(Expense savedExpense, Invoice savedInvoice, ExpenseCategory category,
            ExpenseWriteScope targetScope, ZoneId zoneId, BigDecimal cashAmount, BigDecimal onlineAmount) {
        String paymentNo = sequenceService.generateNextSequence(DocumentType.OUTBOUND_PAYMENT, targetScope.orgId());
        String normalizedMethod = normalizePaymentMethod(savedExpense.getPaymentMethod());
        Payment payment = Payment.builder()
                .paymentType(PaymentType.OUTBOUND)
                .terminalId(savedExpense.getTerminalId())
                .invoiceId(savedInvoice.getId())
                .expenseId(savedExpense.getId())
                .paymentDate(toLocalDateTime(savedExpense.getExpenseDate(), zoneId))
                .paymentMethod(normalizedMethod)
                .amountPaid(savedExpense.getAmount())
                .changeGiven(BigDecimal.ZERO)
                .referenceNo(paymentNo)
                .description(savedExpense.getDescription())
                .docStatus("COMPLETED")
                .isactive("Y")
                .build();
        payment.setClientId(savedExpense.getClientId());
        payment.setOrgId(savedExpense.getOrgId());
        payment.setCreatedBy(savedExpense.getCreatedBy());
        payment.setUpdatedBy(savedExpense.getUpdatedBy());
        Payment savedPayment = paymentRepository.save(payment);

        if ("MIXED".equals(normalizedMethod)) {
            BigDecimal cash = cashAmount != null ? cashAmount : BigDecimal.ZERO;
            BigDecimal online = onlineAmount != null ? onlineAmount : BigDecimal.ZERO;
            if (cash.compareTo(BigDecimal.ZERO) > 0) {
                com.restaurant.pos.order.domain.PaymentSplit cashSplit = com.restaurant.pos.order.domain.PaymentSplit.builder()
                        .paymentId(savedPayment.getId())
                        .paymentMethod("CASH")
                        .amount(cash)
                        .build();
                cashSplit.setClientId(savedExpense.getClientId());
                cashSplit.setOrgId(savedExpense.getOrgId());
                paymentSplitRepository.save(cashSplit);
            }
            if (online.compareTo(BigDecimal.ZERO) > 0) {
                com.restaurant.pos.order.domain.PaymentSplit onlineSplit = com.restaurant.pos.order.domain.PaymentSplit.builder()
                        .paymentId(savedPayment.getId())
                        .paymentMethod("ONLINE")
                        .amount(online)
                        .build();
                onlineSplit.setClientId(savedExpense.getClientId());
                onlineSplit.setOrgId(savedExpense.getOrgId());
                paymentSplitRepository.save(onlineSplit);
            }
        }
        return savedPayment;
    }

    /**
     * Updates an existing expense using an immutability/voiding pattern.
     * Voids the old record and creates a new one to maintain audit integrity.
     */
    @Transactional(timeout = EXPENSE_WRITE_TIMEOUT_SECONDS, rollbackFor = Exception.class)
    public ExpenseResponse updateExpense(UUID id, String idempotencyKey, UpdateExpenseRequest request) {
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        ExpenseWriteScope targetScope = resolveExpenseWriteScope(request);

        // Idempotency check — prevents duplicate void-revision chains
        String cacheKey = String.format("tenant=%s:org=%s:key=%s", 
                contextProvider.getCurrentTenant(), 
                targetScope.cacheKeyPart(), 
                idempotencyKey);
        ExpenseResponse cached = idempotencyStore.get(cacheKey);
        if (cached != null) {
            log.info("Expense update idempotency hit | keyPresent={}", hasIdempotencyKey);
            return cached;
        }

        Expense oldExpense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        validateExpenseAccess(oldExpense);

        if (!oldExpense.isActive()) {
            throw new ExpenseAlreadyVoidedException();
        }

        ExpenseCategory category = getOwnedExpenseCategory(request.getCategoryId(), targetScope.orgId());

        String originalExpenseNo = oldExpense.getExpenseNo();
        int revision = oldExpense.getRevisionNumber() != null ? oldExpense.getRevisionNumber() : 0;
        boolean amountChanged = oldExpense.getAmount().compareTo(request.getAmount()) != 0;
        boolean scopeChanged = !Objects.equals(oldExpense.getOrgId(), targetScope.orgId());
        boolean paymentChainChanged = amountChanged || scopeChanged;

        log.info("Updating expense | id={} | amountChanged={} | keyPresent={}", id, amountChanged, hasIdempotencyKey);

        // 1. VOID the old expense record
        String voidSuffix = "_VOID_" + revision;
        oldExpense.setExpenseNo(originalExpenseNo + voidSuffix);
        oldExpense.setDocStatus(ExpenseStatus.VOID.name());
        oldExpense.deactivate();
        expenseRepository.saveAndFlush(oldExpense);

        // 2. VOID the linked invoices (batched)
        List<Invoice> invoicesToVoid = invoiceRepository.findByExpenseId(id);
        invoicesToVoid.forEach(inv -> {
            accountingPostingService.reverseInvoice(inv, "Expense revised");
            inv.setInvoiceNo(inv.getInvoiceNo() + "_VOID_" + revision);
            inv.setStatus("VOID");
            inv.setDocStatus("VOID");
            inv.setIsactive("N");
        });
        invoiceRepository.saveAll(invoicesToVoid);

        // 3. Handle Payment Logic
        List<Payment> oldPayments = paymentRepository.findByExpenseId(id);
        ZoneId zoneId = contextProvider.getCurrentTimezone();

        // 4. Create NEW Expense record
        Expense newExpense = buildExpenseEntity(request, category, targetScope);
        newExpense.setExpenseNo(scopeChanged
                ? sequenceService.generateNextSequence(DocumentType.EXPENSE, targetScope.orgId())
                : originalExpenseNo);
        newExpense.setRevisionNumber(revision + 1);
        newExpense.setOriginalExpenseId(oldExpense.getId());

        if (!oldPayments.isEmpty() && !paymentChainChanged) {
            newExpense.setPaymentStatus(ExpenseStatus.PENDING.name());
        }

        Expense savedNew = expenseRepository.save(newExpense);

        Invoice savedNewInvoice = createExpenseInvoice(savedNew, category, targetScope, zoneId);

        if (!oldPayments.isEmpty()) {
            if (!paymentChainChanged) {
                // REUSE: Link the first payment to the new expense/invoice
                Payment oldPayment = oldPayments.get(0);
                oldPayment.setExpenseId(savedNew.getId());
                oldPayment.setInvoiceId(savedNewInvoice.getId());
                oldPayment.setOrgId(savedNew.getOrgId());
                paymentRepository.saveAndFlush(oldPayment);

                savedNew.setPaymentStatus(ExpenseStatus.PAID.name());
                expenseRepository.save(savedNew);

                // Re-post accounting entries for new invoice and updated payment (reused)
                postAccountingEntries(savedNewInvoice, oldPayment);
            } else {
                // VOID all old payments (batched)
                oldPayments.forEach(pay -> {
                    accountingPostingService.reversePayment(pay, "Expense revised");
                    pay.setDocStatus("VOID");
                    pay.setIsactive("N");
                    pay.setReferenceNo(pay.getReferenceNo() + "_VOID_" + revision);
                });
                paymentRepository.saveAll(oldPayments);
                paymentRepository.flush();

                // Create new payment
                Payment savedNewPayment = createExpensePayment(savedNew, savedNewInvoice, category, targetScope, zoneId, request.getCashAmount(), request.getOnlineAmount());

                postAccountingEntries(savedNewInvoice, savedNewPayment);
            }
        } else {
            // Create new payment
            Payment savedNewPayment = createExpensePayment(savedNew, savedNewInvoice, category, targetScope, zoneId, request.getCashAmount(), request.getOnlineAmount());

            postAccountingEntries(savedNewInvoice, savedNewPayment);
        }

        log.info("Expense revision finalized | oldId={} | newId={} | rev={} | keyPresent={}", 
                id, savedNew.getId(), revision + 1, hasIdempotencyKey);
        String updaterName = resolveUserDisplayName(savedNew.getUpdatedBy());
        ExpenseResponse response = populateSplits(expenseMapper.toExpenseResponse(savedNew, category.getName(), updaterName), savedNew.getId());

        idempotencyStore.put(cacheKey, response);

        return response;
    }

    /**
     * Voids an expense record, marking it inactive and preserving the audit trail.
     * Returns a confirmation receipt with voided IDs for audit compliance.
     */
    @Transactional(timeout = EXPENSE_WRITE_TIMEOUT_SECONDS, rollbackFor = Exception.class)
    public VoidExpenseResponse voidExpense(UUID id) {
        log.info("Voiding expense record | id={}", id);

        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        validateExpenseAccess(expense);

        if (!expense.isActive()) {
            throw new ExpenseAlreadyVoidedException("Expense is already voided");
        }

        int revision = expense.getRevisionNumber() != null ? expense.getRevisionNumber() : 0;

        // 1. Void the Expense
        expense.setExpenseNo(expense.getExpenseNo() + "_VOID_" + revision);
        expense.setDocStatus(ExpenseStatus.VOID.name());
        expense.deactivate();
        expenseRepository.save(expense);

        // 2. Void linked Invoices (batched)
        List<Invoice> invoicesToVoid = invoiceRepository.findByExpenseId(id);
        List<UUID> invoiceIds = new ArrayList<>();
        invoicesToVoid.forEach(inv -> {
            accountingPostingService.reverseInvoice(inv, "Expense voided");
            inv.setInvoiceNo(inv.getInvoiceNo() + "_VOID_" + revision);
            inv.setStatus("VOID");
            inv.setDocStatus("VOID");
            inv.setIsactive("N");
            invoiceIds.add(inv.getId());
        });
        invoiceRepository.saveAll(invoicesToVoid);

        // 3. Void linked Payments (batched)
        List<Payment> paymentsToVoid = paymentRepository.findByExpenseId(id);
        List<UUID> paymentIds = new ArrayList<>();
        paymentsToVoid.forEach(pay -> {
            accountingPostingService.reversePayment(pay, "Expense voided");
            pay.setReferenceNo(pay.getReferenceNo() + "_VOID_" + revision);
            pay.setDocStatus("VOID");
            pay.setIsactive("N");
            paymentIds.add(pay.getId());
        });
        paymentRepository.saveAll(paymentsToVoid);
        paymentRepository.flush();

        return VoidExpenseResponse.builder()
                .expenseId(id)
                .invoiceId(invoiceIds.isEmpty() ? null : invoiceIds.get(0))
                .paymentIds(paymentIds)
                .voidedAt(Instant.now())
                .message("Expense " + id + " and its financial chain voided successfully")
                .build();
    }

    /**
     * Retrieves a single expense record by ID.
     */
    @Transactional(readOnly = true)
    public ExpenseResponse getExpenseById(UUID id) {
        log.info("Fetching expense details from database | id={}", id);
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        validateExpenseAccess(expense);

        ExpenseCategory category = categoryRepository.findById(expense.getCategoryId())
                .orElse(null);

        String updaterName = resolveUserDisplayName(expense.getUpdatedBy());
        
        log.info("Fetched expense details successfully | id={} | categoryName={} | amount={}", 
                id, category != null ? category.getName() : "Uncategorized", expense.getAmount());
                
        return populateSplits(expenseMapper.toExpenseResponse(expense, category != null ? category.getName() : "Uncategorized",
                updaterName), expense.getId());
    }

    /**
     * Fetches paginated and filtered expense records.
     * Only fetches category names for IDs present on the current page (no
     * full-table scan).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("null")
    public Page<ExpenseResponse> getExpenses(ExpenseSearchCriteria criteria, @NonNull Pageable pageable) {
        UUID clientId = contextProvider.getCurrentTenant();
        UUID orgId = contextProvider.getCurrentOrg();

        log.info("Fetching expenses from database | clientId={} | orgId={} | criteria={}", clientId, orgId, criteria);

        Pageable translatedPageable = translatePageable(pageable);

        Specification<Expense> spec = ExpenseSpecification.filterBy(criteria, clientId, orgId, canUseOrganizationScope());
        Page<Expense> expensePage = expenseRepository.findAll(spec, translatedPageable);

        // Fetch only category names needed for the current page — no full-table read
        Set<UUID> neededCategoryIds = expensePage.getContent().stream()
                .map(Expense::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, String> categoryNames = categoryRepository.findAllById(neededCategoryIds).stream()
                .collect(Collectors.toMap(ExpenseCategory::getId, ExpenseCategory::getName, (a, b) -> a));

        Set<UUID> neededUserIds = expensePage.getContent().stream()
                .map(Expense::getUpdatedBy)
                .filter(Objects::nonNull)
                .map(uidStr -> {
                    try {
                        return UUID.fromString(uidStr);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, String> userNames = neededUserIds
                .isEmpty() ? Map.of()
                        : userRepository.findAllById(neededUserIds).stream()
                                .collect(Collectors.toMap(
                                        u -> u.getId().toString(),
                                        u -> u.getFirstName() + (u.getLastName() != null && !u.getLastName().isBlank()
                                                ? " " + u.getLastName()
                                                : "")));

        log.info("Fetched expenses successfully from database | count={} | totalPages={} | clientId={} | orgId={}",
                expensePage.getNumberOfElements(),
                expensePage.getTotalPages(),
                clientId,
                orgId);

        return expensePage.map(expense -> {
            String updatedByVal = expense.getUpdatedBy();
            String updaterName = updatedByVal == null ? null : userNames.getOrDefault(updatedByVal, updatedByVal);
            ExpenseResponse response = expenseMapper.toExpenseResponse(
                    expense,
                    categoryNames.getOrDefault(expense.getCategoryId(), "Uncategorized"),
                    updaterName);
            return populateSplits(response, expense.getId());
        });
    }

    @Transactional(readOnly = true)
    public BigDecimal getExpenseTotal(ExpenseSearchCriteria criteria) {
        UUID clientId = contextProvider.getCurrentTenant();
        UUID orgId = contextProvider.getCurrentOrg();

        log.info("Calculating total expenses | clientId={} | orgId={} | criteria={}", clientId, orgId, criteria);

        Specification<Expense> spec = ExpenseSpecification.filterBy(criteria, clientId, orgId, canUseOrganizationScope());
        return expenseRepository.findAll(spec).stream()
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    /**
     * Helper to post accounting entries cleanly in a single step.
     */
    private void postAccountingEntries(Invoice invoice, Payment payment) {
        accountingPostingService.postInvoice(null, invoice);
        accountingPostingService.postPayment(null, payment);
    }

    /**
     * Builds an Expense entity from any request implementing ExpenseBaseRequest.
     */
    private Expense buildExpenseEntity(ExpenseBaseRequest request, ExpenseCategory category,
            ExpenseWriteScope targetScope) {
        Instant expenseDate = (request.getExpenseDate() != null) ? request.getExpenseDate() : Instant.now();
        String paymentMethod = (request.getPaymentMethod() != null && !request.getPaymentMethod().isBlank())
                ? request.getPaymentMethod()
                : "CASH";

        Expense expense = Expense.builder()
                .categoryId(category.getId())
                .expenseDate(expenseDate)
                .paymentMethod(paymentMethod)
                .amount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO)
                .description(request.getDescription())
                .docStatus(ExpenseStatus.COMPLETED.name())
                .paymentStatus(ExpenseStatus.PAID.name())
                .activeFlag(Expense.ACTIVE_FLAG)
                .revisionNumber(0)
                .build();

        // Contextual Metadata
        expense.setOrgId(targetScope.orgId());
        expense.setClientId(contextProvider.getCurrentTenant());
        expense.setTerminalId(contextProvider.getCurrentTerminal());

        // Default Currency
        UUID orgId = expense.getOrgId();
        UUID clientId = expense.getClientId();
        if (orgId != null) {
            currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId)
                    .stream().findFirst()
                    .ifPresentOrElse(
                        c -> expense.setCurrencyId(c.getId()),
                        () -> currencyRepository.findByClientIdAndIsDefaultTrue(clientId)
                                .stream().findFirst().ifPresent(c -> expense.setCurrencyId(c.getId()))
                    );
        } else {
            currencyRepository.findByClientIdAndIsDefaultTrue(clientId)
                    .stream().findFirst().ifPresent(c -> expense.setCurrencyId(c.getId()));
        }

        return expense;
    }

    private LocalDateTime toLocalDateTime(Instant instant, ZoneId zoneId) {
        // Stored invoice/payment dates are UTC; the frontend converts them to the branch timezone for display.
        if (instant == null) {
            return LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
        return LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }



    private String normalizePaymentMethod(String paymentMethod) {
        return ExpensePaymentMethod.fromString(paymentMethod).name();
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private ExpenseCategory getOwnedExpenseCategory(UUID categoryId, UUID targetOrgId) {
        return categoryRepository.findByIdAndClientIdAndOrgId(
                categoryId,
                contextProvider.getCurrentTenant(),
                targetOrgId)
                .orElseThrow(() -> new BusinessException("Invalid expense category"));
    }

    private ExpenseWriteScope resolveExpenseWriteScope(ExpenseBaseRequest request) {
        if (request == null) {
            throw new BusinessException("Expense request is required");
        }
        try {
            ExpenseCategoryPolicy.CategoryScope scope = categoryPolicy.resolveWriteScope(
                    request.getScope(),
                    request.getBranchId(),
                    contextProvider);
            return new ExpenseWriteScope(scope.orgId(), scope.scope());
        } catch (org.springframework.security.access.AccessDeniedException ade) {
            throw new BusinessException(ade.getMessage());
        }
    }

    private boolean canUseOrganizationScope() {
        return contextProvider.isSuperAdmin() || contextProvider.hasRole("ADMIN");
    }

    private void validateExpenseAccess(Expense expense) {
        UUID currentOrgId = contextProvider.getCurrentOrg();
        if (canUseOrganizationScope()) {
            return;
        }
        if (currentOrgId == null || !Objects.equals(currentOrgId, expense.getOrgId())) {
            throw new ResourceNotFoundException("Expense not found: " + expense.getId());
        }
    }

    /**
     * Resolves a single user's display name by ID.
     * WARNING: Hits the database per call. Do NOT use this method inside a loop
     * (e.g., when rendering a list of records). Instead, use batched fetch paths.
     */
    private String resolveUserDisplayName(String uidStr) {
        if (uidStr == null || uidStr.isBlank() || "SYSTEM".equalsIgnoreCase(uidStr)) {
            return "SYSTEM";
        }
        try {
            UUID userId = UUID.fromString(uidStr);
            return userRepository.findById(userId)
                    .map(u -> u.getFirstName()
                            + (u.getLastName() != null && !u.getLastName().isBlank() ? " " + u.getLastName() : ""))
                    .orElse(uidStr);
        } catch (Exception e) {
            return uidStr;
        }
    }

    private record ExpenseWriteScope(UUID orgId, String scope) {
        private String cacheKeyPart() {
            return orgId == null ? "GLOBAL" : orgId.toString();
        }
    }

    private ExpenseResponse populateSplits(ExpenseResponse response, UUID expenseId) {
        if (!"MIXED".equalsIgnoreCase(response.getPaymentMethod())) {
            return response;
        }
        return paymentRepository.findByExpenseId(expenseId).stream()
                .filter(p -> "Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus()))
                .findFirst()
                .map(p -> {
                    List<com.restaurant.pos.order.domain.PaymentSplit> splits = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(p.getId());
                    BigDecimal cash = BigDecimal.ZERO;
                    BigDecimal online = BigDecimal.ZERO;
                    for (com.restaurant.pos.order.domain.PaymentSplit split : splits) {
                        if ("CASH".equalsIgnoreCase(split.getPaymentMethod())) {
                            cash = split.getAmount();
                        } else if ("ONLINE".equalsIgnoreCase(split.getPaymentMethod())) {
                            online = split.getAmount();
                        }
                    }
                    return response.toBuilder()
                            .cashAmount(cash)
                            .onlineAmount(online)
                            .build();
                })
                .orElse(response);
    }
}
