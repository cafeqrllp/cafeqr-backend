package com.restaurant.pos.order.service;

import com.restaurant.pos.common.service.CurrencyScaleService;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.dto.report.PaymentBalanceReportDto;
import com.restaurant.pos.order.dto.report.PaymentTypeBalanceDto;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.paymenttype.repository.PaymentTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dedicated Enterprise Service solely responsible for calculating and reporting
 * the financial movement, inflows, outflows, and net balances for each payment type.
 *
 * <p>Money is counted when it actually moves (payment date), so a credit sale settled
 * later shows up in the tender it was settled with, on the day it was settled.
 * CREDIT is not a tender: it tracks receivables raised vs settled and is kept out of
 * the collected / paid-out totals.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentBalanceReportService {

    private static final String CREDIT_KEY = "CREDIT";

    /** Order payments are stamped with the order date; anything later is a settlement. */
    private static final Duration SETTLEMENT_GAP = Duration.ofMinutes(1);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final ExpenseRepository expenseRepository;
    private final PaymentTypeRepository paymentTypeRepository;
    private final InvoiceRepository invoiceRepository;
    private final CurrencyScaleService currencyScale;

    private static class Accumulator {
        String paymentMethod;
        String displayName;
        String category = "OTHERS";
        boolean isConfigured = false;
        int sortOrder = 999;

        BigDecimal salesAmount = BigDecimal.ZERO;
        BigDecimal collectionAmount = BigDecimal.ZERO;
        BigDecimal inflowAmount = BigDecimal.ZERO;
        long inflowCount = 0;

        BigDecimal expenseAmount = BigDecimal.ZERO;
        BigDecimal purchaseAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = BigDecimal.ZERO;
        BigDecimal outflowAmount = BigDecimal.ZERO;
        long outflowCount = 0;
    }

    private enum Flow { SALE, COLLECTION, EXPENSE, PURCHASE, REFUND }

    public PaymentBalanceReportDto getPaymentTypeBalances(Instant from, Instant to, UUID orgId, UUID terminalId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID resolvedOrgId = SecurityUtils.isSuperAdmin() ? orgId : TenantContext.getCurrentOrg();
        int dp = currencyScale.decimalPlaces(clientId, resolvedOrgId);

        // 1. Initialize buckets from configured Payment Types in the database
        Map<String, Accumulator> buckets = new LinkedHashMap<>();
        List<com.restaurant.pos.paymenttype.domain.PaymentType> configuredTypes;
        if (resolvedOrgId != null) {
            configuredTypes = paymentTypeRepository.findByClientIdAndOrgIdOrderBySortOrderAscDisplayNameAsc(clientId, resolvedOrgId);
        } else {
            configuredTypes = paymentTypeRepository.findByClientIdOrderBySortOrderAscDisplayNameAsc(clientId);
        }

        if (configuredTypes != null) {
            for (com.restaurant.pos.paymenttype.domain.PaymentType pt : configuredTypes) {
                if (pt != null && "Y".equalsIgnoreCase(pt.getIsactive())) {
                    String key = normalizeKey(pt.getDisplayName());
                    if (isMixedOrComposite(key) || isMixedOrComposite(pt.getDisplayName()) || isMixedOrComposite(pt.getPaymentType())) {
                        continue; // Skip composite/split tenders from balance accounts
                    }
                    Accumulator acc = buckets.computeIfAbsent(key, k -> new Accumulator());
                    acc.paymentMethod = key;
                    acc.displayName = pt.getDisplayName();
                    acc.category = pt.getPaymentType() != null ? pt.getPaymentType() : "OTHERS";
                    acc.sortOrder = pt.getSortOrder() != null ? pt.getSortOrder() : 999;
                    acc.isConfigured = true;
                }
            }
        }

        // Standard fallback buckets if not yet configured
        ensureBucket(buckets, "CASH", "Cash", "OTHERS", 1);
        ensureBucket(buckets, "ONLINE", "Online", "OTHERS", 2);
        ensureBucket(buckets, CREDIT_KEY, "Credit", "CREDIT", 4);

        // 2. All active payments whose payment date falls in the period.
        // payment_date is persisted as UTC wall-clock time, so convert the range with UTC (not the business zone).
        LocalDateTime utcFrom = from != null ? LocalDateTime.ofInstant(from, ZoneOffset.UTC) : null;
        LocalDateTime utcTo = to != null ? LocalDateTime.ofInstant(to, ZoneOffset.UTC) : null;
        List<Payment> periodPayments = paymentRepository.findActivePaymentsInPeriod(clientId, resolvedOrgId, utcFrom, utcTo)
                .stream()
                .filter(p -> p != null && isActivePayment(p))
                .filter(p -> terminalId == null || terminalId.equals(p.getTerminalId()))
                .collect(Collectors.toList());

        Set<UUID> linkedOrderIds = periodPayments.stream().map(Payment::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Order> ordersById = linkedOrderIds.isEmpty() ? Map.of()
                : orderRepository.findAllById(linkedOrderIds).stream()
                .collect(Collectors.toMap(Order::getId, o -> o, (a, b) -> a));

        Set<UUID> paymentIds = periodPayments.stream().map(Payment::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, List<PaymentSplit>> splitsByPaymentId = paymentIds.isEmpty() ? Map.of()
                : paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds).stream()
                .collect(Collectors.groupingBy(PaymentSplit::getPaymentId, LinkedHashMap::new, Collectors.toList()));

        for (Payment p : periodPayments) {
            Order order = p.getOrderId() != null ? ordersById.get(p.getOrderId()) : null;
            Flow flow = classify(p, order);
            if (flow == null) {
                continue;
            }
            if (flow == Flow.EXPENSE && p.getExpenseId() != null) {
                continue; // Expense-linked payments are taken from the expenses ledger below
            }
            List<PaymentSplit> splits = splitsByPaymentId.getOrDefault(p.getId(), List.of());
            if (splits.isEmpty()) {
                record(buckets, normalizeKey(p.getPaymentMethod()), safe(p.getAmountPaid()), flow, dp);
            } else {
                for (PaymentSplit split : splits) {
                    record(buckets, normalizeKey(split.getPaymentMethod()), safe(split.getAmount()), flow, dp);
                }
            }
        }

        // 3. Credit sales raised in the period (receivable, not money in hand)
        Map<UUID, BigDecimal> paidAtSaleByOrder = new HashMap<>();
        Set<UUID> ordersWithCreditRow = new HashSet<>();
        for (Payment p : periodPayments) {
            Order order = p.getOrderId() != null ? ordersById.get(p.getOrderId()) : null;
            if (classify(p, order) != Flow.SALE) {
                continue;
            }
            if (CREDIT_KEY.equals(normalizeKey(p.getPaymentMethod()))) {
                ordersWithCreditRow.add(p.getOrderId());
            } else {
                paidAtSaleByOrder.merge(p.getOrderId(), safe(p.getAmountPaid()), BigDecimal::add);
            }
        }
        for (Order o : fetchCreditSaleOrders(from, to, clientId, resolvedOrgId, terminalId)) {
            // Credit-method payment rows were already recorded against the CREDIT bucket in step 2
            if (ordersWithCreditRow.contains(o.getId())) {
                continue;
            }
            BigDecimal onAccount = safe(o.getGrandTotal()).subtract(paidAtSaleByOrder.getOrDefault(o.getId(), BigDecimal.ZERO));
            record(buckets, CREDIT_KEY, onAccount, Flow.SALE, dp);
        }

        // 4. Credit settled in the period: every collection received against a credit customer
        for (Payment p : periodPayments) {
            Order order = p.getOrderId() != null ? ordersById.get(p.getOrderId()) : null;
            if (classify(p, order) == Flow.COLLECTION && p.getCreditCustomerId() != null
                    && !CREDIT_KEY.equals(normalizeKey(p.getPaymentMethod()))) {
                Accumulator credit = buckets.get(CREDIT_KEY);
                credit.collectionAmount = credit.collectionAmount.add(safe(p.getAmountPaid()));
                credit.outflowAmount = credit.outflowAmount.add(safe(p.getAmountPaid()));
                credit.outflowCount++;
            }
        }

        // 5. Operating expenses from the expenses ledger
        List<Expense> expensesList = expenseRepository.findByClientIdAndOrgIdAndExpenseDateBetweenOrderByExpenseDateAsc(
                clientId, resolvedOrgId, from, to);
        if (expensesList != null) {
            for (Expense e : expensesList) {
                if (e == null || !e.isActive() || "VOID".equalsIgnoreCase(e.getDocStatus())) {
                    continue;
                }
                if (terminalId != null && !terminalId.equals(e.getTerminalId())) {
                    continue;
                }
                record(buckets, normalizeKey(e.getPaymentMethod()), safe(e.getAmount()), Flow.EXPENSE, dp);
            }
        }

        // 6. Build response DTOs and calculate Net Balances
        BigDecimal grandInflow = BigDecimal.ZERO;
        BigDecimal grandOutflow = BigDecimal.ZERO;
        long totalInflowCount = 0;
        long totalOutflowCount = 0;

        List<PaymentTypeBalanceDto> dtoList = new ArrayList<>();
        for (Accumulator acc : buckets.values()) {
            if (isMixedOrComposite(acc.paymentMethod) || isMixedOrComposite(acc.displayName)) {
                continue; // Do not display composite/split payments as a separate tender
            }
            boolean receivable = isReceivable(acc);
            if (!receivable) {
                grandInflow = grandInflow.add(acc.inflowAmount);
                grandOutflow = grandOutflow.add(acc.outflowAmount);
                totalInflowCount += acc.inflowCount;
                totalOutflowCount += acc.outflowCount;
            }

            BigDecimal netBal = acc.inflowAmount.subtract(acc.outflowAmount);
            String status = "SETTLED";
            if (netBal.compareTo(BigDecimal.ZERO) > 0) {
                status = "SURPLUS";
            } else if (netBal.compareTo(BigDecimal.ZERO) < 0) {
                status = "DEFICIT";
            }

            BigDecimal avgTxn = acc.inflowCount > 0
                    ? acc.inflowAmount.divide(BigDecimal.valueOf(acc.inflowCount), dp, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            PaymentTypeBalanceDto dto = PaymentTypeBalanceDto.builder()
                    .paymentMethod(acc.paymentMethod)
                    .displayName(acc.displayName != null ? acc.displayName : formatDisplayName(acc.paymentMethod))
                    .category(acc.category)
                    .isConfigured(acc.isConfigured)
                    .isReceivable(receivable)
                    .sortOrder(acc.sortOrder)
                    .inflowAmount(acc.inflowAmount)
                    .inflowCount(acc.inflowCount)
                    .salesAmount(acc.salesAmount)
                    .collectionAmount(acc.collectionAmount)
                    .outflowAmount(acc.outflowAmount)
                    .outflowCount(acc.outflowCount)
                    .expenseAmount(acc.expenseAmount)
                    .purchaseAmount(acc.purchaseAmount)
                    .refundAmount(acc.refundAmount)
                    .netBalance(netBal)
                    .status(status)
                    .averageTransaction(avgTxn)
                    .build();

            dtoList.add(dto);
        }

        // Share of money collected per tender (receivables have no share of cash collected)
        final BigDecimal totalInflowRef = grandInflow;
        for (PaymentTypeBalanceDto dto : dtoList) {
            if (!dto.isReceivable() && totalInflowRef.compareTo(BigDecimal.ZERO) > 0) {
                dto.setPercentage(dto.getInflowAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalInflowRef, 1, RoundingMode.HALF_UP));
            } else {
                dto.setPercentage(BigDecimal.ZERO);
            }
        }

        // Sort: configured types by sortOrder, followed by non-configured types by inflow
        dtoList.sort((a, b) -> {
            if (a.isConfigured() != b.isConfigured()) {
                return a.isConfigured() ? -1 : 1;
            }
            int orderCmp = Integer.compare(
                    a.getSortOrder() != null ? a.getSortOrder() : 999,
                    b.getSortOrder() != null ? b.getSortOrder() : 999
            );
            if (orderCmp != 0) return orderCmp;
            return b.getInflowAmount().compareTo(a.getInflowAmount());
        });

        CreditTotals credit = creditTotals(clientId, resolvedOrgId, utcFrom, utcTo, dp);

        return PaymentBalanceReportDto.builder()
                .from(from)
                .to(to)
                .orgId(resolvedOrgId)
                .terminalId(terminalId)
                .totalInflow(grandInflow)
                .totalOutflow(grandOutflow)
                .netBalance(grandInflow.subtract(grandOutflow))
                .creditGiven(credit.given())
                .creditCollected(credit.collected())
                .creditBalance(credit.balance())
                .totalInflowCount(totalInflowCount)
                .totalOutflowCount(totalOutflowCount)
                .balances(dtoList)
                .build();
    }

    /**
     * Decides what a payment represents. Returns null for payments that should not be counted
     * (e.g. against a sale that never completed or was cancelled).
     */
    private Flow classify(Payment p, Order order) {
        boolean outbound = p.getPaymentType() == com.restaurant.pos.order.domain.PaymentType.OUTBOUND;
        if (outbound) {
            if (p.getExpenseId() != null) return Flow.EXPENSE;
            if (order == null) return Flow.PURCHASE; // Vendor settlement without a linked bill
            if (order.getOrderType() == OrderType.SALE) return Flow.REFUND;
            if (order.getOrderType() == OrderType.EXPENSE) return Flow.EXPENSE;
            return Flow.PURCHASE;
        }
        if (order == null || order.getOrderType() != OrderType.SALE) {
            return Flow.COLLECTION;
        }
        if (isLaterSettlement(p, order)) {
            return Flow.COLLECTION;
        }
        boolean completed = "COMPLETED".equalsIgnoreCase(order.getOrderStatus()) && order.isActive();
        return completed ? Flow.SALE : null;
    }

    /** A payment against a sale dated after the sale itself was a later settlement of that bill. */
    private boolean isLaterSettlement(Payment p, Order order) {
        if (p.getPaymentDate() == null || order.getOrderDate() == null) {
            return false;
        }
        LocalDateTime orderUtc = LocalDateTime.ofInstant(order.getOrderDate(), ZoneOffset.UTC);
        return Duration.between(orderUtc, p.getPaymentDate()).compareTo(SETTLEMENT_GAP) > 0;
    }

    private record CreditTotals(BigDecimal given, BigDecimal collected, BigDecimal balance) {}

    /**
     * Credit figures for the selected dates, using the same rules as the Credit report
     * (credit-sale invoices of credit customers vs inbound payments from credit customers):
     * given / collected = within the period, balance = given - collected for that period.
     * Invoice and payment dates are stored as UTC wall-clock time.
     */
    private CreditTotals creditTotals(UUID clientId, UUID orgId, LocalDateTime utcFrom, LocalDateTime utcTo, int dp) {
        List<Invoice> invoices = invoiceRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
            predicates.add(cb.isNotNull(root.get("creditCustomerId")));
            predicates.add(cb.isTrue(root.get("isCredit")));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.not(cb.upper(cb.coalesce(root.get("status"), "UNPAID")).in("VOID", "VOIDED", "CANCELLED", "INACTIVE")));
            if (utcFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), utcFrom));
            }
            if (utcTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), utcTo));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        List<Payment> payments = paymentRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.isNotNull(root.get("creditCustomerId")));
            // Customer settlements only; vendor payouts also carry creditCustomerId but are OUTBOUND.
            predicates.add(cb.equal(root.get("paymentType"), com.restaurant.pos.order.domain.PaymentType.INBOUND));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.not(cb.upper(cb.coalesce(root.get("docStatus"), "COMPLETED")).in("VOID", "VOIDED", "CANCELLED", "INACTIVE")));
            if (utcFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), utcFrom));
            }
            if (utcTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), utcTo));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        BigDecimal given = invoices.stream().map(Invoice::getTotalAmount).map(this::safe).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = payments.stream().map(Payment::getAmountPaid).map(this::safe).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = given.subtract(collected);
        return new CreditTotals(
                given.setScale(dp, RoundingMode.HALF_UP),
                collected.setScale(dp, RoundingMode.HALF_UP),
                balance.setScale(dp, RoundingMode.HALF_UP));
    }

    private boolean isReceivable(Accumulator acc) {
        return CREDIT_KEY.equals(acc.paymentMethod) || "CREDIT".equalsIgnoreCase(acc.category);
    }

    private void ensureBucket(Map<String, Accumulator> buckets, String key, String name, String category, int sort) {
        if (!buckets.containsKey(key)) {
            Accumulator acc = new Accumulator();
            acc.paymentMethod = key;
            acc.displayName = name;
            acc.category = category;
            acc.sortOrder = sort;
            acc.isConfigured = true;
            buckets.put(key, acc);
        }
    }

    private void record(Map<String, Accumulator> buckets, String key, BigDecimal amount, Flow flow, int dp) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (isMixedOrComposite(key)) {
            // Legacy MIXED rows without split detail: divide evenly between Cash and Online
            BigDecimal half = amount.divide(BigDecimal.valueOf(2), dp, RoundingMode.HALF_UP);
            BigDecimal rem = amount.subtract(half);
            record(buckets, "CASH", half, flow, dp);
            record(buckets, "ONLINE", rem, flow, dp);
            return;
        }
        Accumulator acc = buckets.computeIfAbsent(key, k -> {
            Accumulator a = new Accumulator();
            a.paymentMethod = k;
            a.displayName = formatDisplayName(k);
            return a;
        });
        switch (flow) {
            case SALE -> {
                acc.salesAmount = acc.salesAmount.add(amount);
                acc.inflowAmount = acc.inflowAmount.add(amount);
                acc.inflowCount++;
            }
            case COLLECTION -> {
                acc.collectionAmount = acc.collectionAmount.add(amount);
                acc.inflowAmount = acc.inflowAmount.add(amount);
                acc.inflowCount++;
            }
            case EXPENSE -> {
                acc.expenseAmount = acc.expenseAmount.add(amount);
                acc.outflowAmount = acc.outflowAmount.add(amount);
                acc.outflowCount++;
            }
            case PURCHASE -> {
                acc.purchaseAmount = acc.purchaseAmount.add(amount);
                acc.outflowAmount = acc.outflowAmount.add(amount);
                acc.outflowCount++;
            }
            case REFUND -> {
                acc.refundAmount = acc.refundAmount.add(amount);
                acc.outflowAmount = acc.outflowAmount.add(amount);
                acc.outflowCount++;
            }
        }
    }

    private boolean isMixedOrComposite(String method) {
        if (method == null || method.isBlank()) return false;
        String m = method.trim().toUpperCase(Locale.ROOT);
        return m.contains("MIXED") || m.contains("SPLIT");
    }

    private List<Order> fetchCreditSaleOrders(Instant from, Instant to, UUID clientId, UUID resolvedOrgId, UUID terminalId) {
        return orderRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (resolvedOrgId != null) {
                predicates.add(cb.equal(root.get("orgId"), resolvedOrgId));
            }
            if (terminalId != null) {
                predicates.add(cb.equal(root.get("terminalId"), terminalId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.SALE));
            predicates.add(cb.equal(root.get("orderStatus"), "COMPLETED"));
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            predicates.add(cb.isTrue(root.get("isCredit")));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), to));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private String normalizeKey(String method) {
        if (method == null || method.isBlank()) {
            return "UNASSIGNED";
        }
        return method.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private String formatDisplayName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String lower = key.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private boolean isActivePayment(Payment p) {
        return p != null
                && !"N".equalsIgnoreCase(p.getIsactive())
                && !"VOID".equalsIgnoreCase(p.getDocStatus())
                && !"VOIDED".equalsIgnoreCase(p.getDocStatus());
    }

    private BigDecimal safe(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }
}
