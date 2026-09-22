package com.restaurant.pos.order.service;

import com.restaurant.pos.common.context.TimezoneResolver;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dedicated Enterprise Service solely responsible for calculating and reporting
 * the financial movement, inflows, outflows, and net balances for each payment type.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentBalanceReportService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final ExpenseRepository expenseRepository;
    private final PaymentTypeRepository paymentTypeRepository;
    private final TimezoneResolver timezoneResolver;

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
        BigDecimal outflowAmount = BigDecimal.ZERO;
        long outflowCount = 0;
    }

    public PaymentBalanceReportDto getPaymentTypeBalances(Instant from, Instant to, UUID orgId, UUID terminalId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID resolvedOrgId = SecurityUtils.isSuperAdmin() ? orgId : TenantContext.getCurrentOrg();

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
        ensureBucket(buckets, "CREDIT", "Credit", "CREDIT", 4);

        // 2. Fetch and aggregate Sales Inflows in the period
        List<Order> orders = fetchSaleOrders(from, to, clientId, resolvedOrgId, terminalId);
        List<UUID> orderIds = orders.stream().map(Order::getId).filter(Objects::nonNull).collect(Collectors.toList());

        Map<UUID, List<Payment>> paymentsByOrder = new HashMap<>();
        List<Payment> orderPayments = new ArrayList<>();
        if (!orderIds.isEmpty()) {
            paymentRepository.findByOrderIdIn(orderIds).forEach(pay -> {
                if (pay.getOrderId() != null && isActivePayment(pay)) {
                    paymentsByOrder.computeIfAbsent(pay.getOrderId(), k -> new ArrayList<>()).add(pay);
                    orderPayments.add(pay);
                }
            });
        }

        Set<UUID> paymentIds = orderPayments.stream().map(Payment::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, List<PaymentSplit>> splitsByPaymentId = paymentIds.isEmpty() ? Map.of()
                : paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds).stream()
                .collect(Collectors.groupingBy(PaymentSplit::getPaymentId, LinkedHashMap::new, Collectors.toList()));

        for (Order o : orders) {
            List<Payment> payments = paymentsByOrder.getOrDefault(o.getId(), List.of());
            if (payments.isEmpty()) {
                if (Boolean.TRUE.equals(o.getIsCredit())) {
                    recordInflow(buckets, "CREDIT", safe(o.getGrandTotal()), true);
                } else {
                    recordInflow(buckets, "UNASSIGNED", safe(o.getGrandTotal()), true);
                }
            } else {
                for (Payment p : payments) {
                    List<PaymentSplit> splits = splitsByPaymentId.getOrDefault(p.getId(), List.of());
                    if (splits.isEmpty()) {
                        String method = normalizeKey(p.getPaymentMethod());
                        if (isMixedOrComposite(method)) {
                            BigDecimal half = safe(p.getAmountPaid()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                            BigDecimal rem = safe(p.getAmountPaid()).subtract(half);
                            recordInflow(buckets, "CASH", half, true);
                            recordInflow(buckets, "ONLINE", rem, true);
                        } else {
                            recordInflow(buckets, method, safe(p.getAmountPaid()), true);
                        }
                    } else {
                        for (PaymentSplit split : splits) {
                            String splitMethod = normalizeKey(split.getPaymentMethod());
                            if (isMixedOrComposite(splitMethod)) {
                                BigDecimal half = safe(split.getAmount()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                                BigDecimal rem = safe(split.getAmount()).subtract(half);
                                recordInflow(buckets, "CASH", half, true);
                                recordInflow(buckets, "ONLINE", rem, true);
                            } else {
                                recordInflow(buckets, splitMethod, safe(split.getAmount()), true);
                            }
                        }
                    }
                }
            }
        }

        // 3. Fetch and aggregate Standalone Inbound Collections (Customer debt settlements)
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, resolvedOrgId);
        LocalDateTime localFrom = from != null ? LocalDateTime.ofInstant(from, zoneId) : null;
        LocalDateTime localTo = to != null ? LocalDateTime.ofInstant(to, zoneId) : null;

        List<Payment> activePeriodPayments = paymentRepository.findActivePaymentsInPeriod(clientId, resolvedOrgId, localFrom, localTo);
        Set<UUID> orderPaymentIdSet = orderPayments.stream().map(Payment::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        for (Payment p : activePeriodPayments) {
            if (p == null || orderPaymentIdSet.contains(p.getId())) {
                continue;
            }
            if (p.getPaymentType() == null || p.getPaymentType() == com.restaurant.pos.order.domain.PaymentType.INBOUND) {
                // Non-order inbound payment = credit customer settlement / collection
                String method = normalizeKey(p.getPaymentMethod());
                if (isMixedOrComposite(method)) {
                    BigDecimal half = safe(p.getAmountPaid()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    BigDecimal rem = safe(p.getAmountPaid()).subtract(half);
                    recordInflowCollection(buckets, "CASH", half);
                    recordInflowCollection(buckets, "ONLINE", rem);
                } else {
                    recordInflowCollection(buckets, method, safe(p.getAmountPaid()));
                }
            }
        }

        // 4. Fetch and aggregate Operating Expenses Outflows in the period
        List<Expense> expensesList = expenseRepository.findByClientIdAndOrgIdAndExpenseDateBetweenOrderByExpenseDateAsc(
                clientId, resolvedOrgId, from, to);

        if (expensesList != null) {
            for (Expense e : expensesList) {
                if (e != null && e.isActive() && "COMPLETED".equalsIgnoreCase(e.getDocStatus())) {
                    String method = normalizeKey(e.getPaymentMethod());
                    if (isMixedOrComposite(method)) {
                        BigDecimal half = safe(e.getAmount()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                        BigDecimal rem = safe(e.getAmount()).subtract(half);
                        recordExpenseOutflow(buckets, "CASH", half);
                        recordExpenseOutflow(buckets, "ONLINE", rem);
                    } else {
                        recordExpenseOutflow(buckets, method, safe(e.getAmount()));
                    }
                }
            }
        }

        // 5. Fetch and aggregate Vendor Purchase Outflows in the period
        for (Payment p : activePeriodPayments) {
            if (p != null && p.getPaymentType() == com.restaurant.pos.order.domain.PaymentType.OUTBOUND) {
                // If linked to an expense, it was already accounted for in expensesList above
                if (p.getExpenseId() == null) {
                    String method = normalizeKey(p.getPaymentMethod());
                    if (isMixedOrComposite(method)) {
                        BigDecimal half = safe(p.getAmountPaid()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                        BigDecimal rem = safe(p.getAmountPaid()).subtract(half);
                        recordPurchaseOutflow(buckets, "CASH", half);
                        recordPurchaseOutflow(buckets, "ONLINE", rem);
                    } else {
                        recordPurchaseOutflow(buckets, method, safe(p.getAmountPaid()));
                    }
                }
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
            grandInflow = grandInflow.add(acc.inflowAmount);
            grandOutflow = grandOutflow.add(acc.outflowAmount);
            totalInflowCount += acc.inflowCount;
            totalOutflowCount += acc.outflowCount;

            BigDecimal netBal = acc.inflowAmount.subtract(acc.outflowAmount);
            String status = "SETTLED";
            if (netBal.compareTo(BigDecimal.ZERO) > 0) {
                status = "SURPLUS";
            } else if (netBal.compareTo(BigDecimal.ZERO) < 0) {
                status = "DEFICIT";
            }

            BigDecimal avgTxn = acc.inflowCount > 0
                    ? acc.inflowAmount.divide(BigDecimal.valueOf(acc.inflowCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            PaymentTypeBalanceDto dto = PaymentTypeBalanceDto.builder()
                    .paymentMethod(acc.paymentMethod)
                    .displayName(acc.displayName != null ? acc.displayName : formatDisplayName(acc.paymentMethod))
                    .category(acc.category)
                    .isConfigured(acc.isConfigured)
                    .sortOrder(acc.sortOrder)
                    .inflowAmount(acc.inflowAmount)
                    .inflowCount(acc.inflowCount)
                    .salesAmount(acc.salesAmount)
                    .collectionAmount(acc.collectionAmount)
                    .outflowAmount(acc.outflowAmount)
                    .outflowCount(acc.outflowCount)
                    .expenseAmount(acc.expenseAmount)
                    .purchaseAmount(acc.purchaseAmount)
                    .netBalance(netBal)
                    .status(status)
                    .averageTransaction(avgTxn)
                    .build();

            dtoList.add(dto);
        }

        // Calculate inflow percentage per method
        final BigDecimal totalInflowRef = grandInflow;
        for (PaymentTypeBalanceDto dto : dtoList) {
            if (totalInflowRef.compareTo(BigDecimal.ZERO) > 0) {
                dto.setPercentage(dto.getInflowAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalInflowRef, 1, RoundingMode.HALF_UP));
            } else {
                dto.setPercentage(BigDecimal.ZERO);
            }
        }

        // Sort: configured types by sortOrder, followed by non-configured types by net balance
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

        return PaymentBalanceReportDto.builder()
                .from(from)
                .to(to)
                .orgId(resolvedOrgId)
                .terminalId(terminalId)
                .totalInflow(grandInflow)
                .totalOutflow(grandOutflow)
                .netBalance(grandInflow.subtract(grandOutflow))
                .totalInflowCount(totalInflowCount)
                .totalOutflowCount(totalOutflowCount)
                .balances(dtoList)
                .build();
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

    private void recordInflow(Map<String, Accumulator> buckets, String key, BigDecimal amount, boolean isSales) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (isMixedOrComposite(key)) {
            BigDecimal half = amount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal rem = amount.subtract(half);
            recordInflow(buckets, "CASH", half, isSales);
            recordInflow(buckets, "ONLINE", rem, isSales);
            return;
        }
        Accumulator acc = buckets.computeIfAbsent(key, k -> {
            Accumulator a = new Accumulator();
            a.paymentMethod = k;
            a.displayName = formatDisplayName(k);
            return a;
        });
        if (isSales) {
            acc.salesAmount = acc.salesAmount.add(amount);
        }
        acc.inflowAmount = acc.inflowAmount.add(amount);
        acc.inflowCount++;
    }

    private void recordInflowCollection(Map<String, Accumulator> buckets, String key, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (isMixedOrComposite(key)) {
            BigDecimal half = amount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal rem = amount.subtract(half);
            recordInflowCollection(buckets, "CASH", half);
            recordInflowCollection(buckets, "ONLINE", rem);
            return;
        }
        Accumulator acc = buckets.computeIfAbsent(key, k -> {
            Accumulator a = new Accumulator();
            a.paymentMethod = k;
            a.displayName = formatDisplayName(k);
            return a;
        });
        acc.collectionAmount = acc.collectionAmount.add(amount);
        acc.inflowAmount = acc.inflowAmount.add(amount);
        acc.inflowCount++;
    }

    private void recordExpenseOutflow(Map<String, Accumulator> buckets, String key, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (isMixedOrComposite(key)) {
            BigDecimal half = amount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal rem = amount.subtract(half);
            recordExpenseOutflow(buckets, "CASH", half);
            recordExpenseOutflow(buckets, "ONLINE", rem);
            return;
        }
        Accumulator acc = buckets.computeIfAbsent(key, k -> {
            Accumulator a = new Accumulator();
            a.paymentMethod = k;
            a.displayName = formatDisplayName(k);
            return a;
        });
        acc.expenseAmount = acc.expenseAmount.add(amount);
        acc.outflowAmount = acc.outflowAmount.add(amount);
        acc.outflowCount++;
    }

    private void recordPurchaseOutflow(Map<String, Accumulator> buckets, String key, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (isMixedOrComposite(key)) {
            BigDecimal half = amount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal rem = amount.subtract(half);
            recordPurchaseOutflow(buckets, "CASH", half);
            recordPurchaseOutflow(buckets, "ONLINE", rem);
            return;
        }
        Accumulator acc = buckets.computeIfAbsent(key, k -> {
            Accumulator a = new Accumulator();
            a.paymentMethod = k;
            a.displayName = formatDisplayName(k);
            return a;
        });
        acc.purchaseAmount = acc.purchaseAmount.add(amount);
        acc.outflowAmount = acc.outflowAmount.add(amount);
        acc.outflowCount++;
    }

    private boolean isMixedOrComposite(String method) {
        if (method == null || method.isBlank()) return false;
        String m = method.trim().toUpperCase(Locale.ROOT);
        return m.equals("MIXED") || m.equals("SPLIT") || m.equals("MIXED_PAYMENT") || m.contains("MIXED") || m.contains("SPLIT");
    }

    private List<Order> fetchSaleOrders(Instant from, Instant to, UUID clientId, UUID resolvedOrgId, UUID terminalId) {
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
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), to));
            }
            query.orderBy(cb.desc(root.get("orderDate")));
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
