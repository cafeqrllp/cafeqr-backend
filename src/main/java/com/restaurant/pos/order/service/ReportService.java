package com.restaurant.pos.order.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.accounting.dto.AccountingSummaryDto;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.accounting.service.AccountingService;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.dto.OrderCustomerDto;
import com.restaurant.pos.order.dto.report.*;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final CustomerRepository customerRepository;
    private final AccountingPostingService accountingPostingService;
    private final AccountingService accountingService;
    private final OrganizationRepository organizationRepository;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ─── Sales Summary ──────────────────────────────────────────────────────

    public SalesSummaryDto getSalesSummary(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        long totalOrders = orders.size();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        long itemsSold = 0;

        for (Order o : orders) {
            totalRevenue = totalRevenue.add(safe(o.getTotalAmount()));
            totalTax = totalTax.add(safe(o.getTotalTaxAmount()));
            totalDiscount = totalDiscount.add(safe(o.getTotalDiscountAmount()));
            grandTotal = grandTotal.add(safe(o.getGrandTotal()));

            if (o.getLines() != null) {
                for (OrderLine line : o.getLines()) {
                    if (line.isActive()) {
                        itemsSold += safe(line.getQuantity()).longValue();
                    }
                }
            }
        }

        BigDecimal avg = totalOrders > 0
                ? grandTotal.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return SalesSummaryDto.builder()
                .totalOrders(totalOrders)
                .totalRevenue(totalRevenue)
                .avgOrderValue(avg)
                .itemsSold(itemsSold)
                .totalTax(totalTax)
                .totalDiscount(totalDiscount)
                .grandTotal(grandTotal)
                .build();
    }

    // ─── Sales Orders (with lines) ──────────────────────────────────────────

    public List<OrderReportDto> getSalesOrders(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        return orders.stream().map(o -> {
            List<OrderReportDto.OrderLineReportDto> lines = (o.getLines() == null) ? List.of()
                    : o.getLines().stream()
                        .filter(OrderLine::isActive)
                        .map(l -> OrderReportDto.OrderLineReportDto.builder()
                                .productId(l.getProductId())
                                .productName(l.getProductName())
                                .categoryName(l.getCategoryName())
                                .quantity(safe(l.getQuantity()))
                                .unitPrice(safe(l.getUnitPrice()))
                                .taxRate(safe(l.getTaxRate()))
                                .taxAmount(safe(l.getTaxAmount()))
                                .discountAmount(safe(l.getDiscountAmount()))
                                .lineTotal(safe(l.getLineTotal()))
                                .build())
                        .collect(Collectors.toList());

            Instant od = o.getOrderDate();
            List<OrderCustomerDto> customers = linkedCustomers(o);
            return OrderReportDto.builder()
                    .id(o.getId())
                    .orderNo(o.getOrderNo())
                    .invoiceNo(o.getInvoiceNo())
                    .paymentNo(o.getPaymentNo())
                    .orderStatus(o.getOrderStatus())
                    .paymentStatus(o.getPaymentStatus())
                    .fulfillmentType(o.getFulfillmentType())
                    .tableNumber(o.getTableNumber())
                    .customerName(customerDisplay(customers))
                    .customerPhone(customerPhoneDisplay(customers))
                    .customers(customers)
                    .totalAmount(safe(o.getTotalAmount()))
                    .totalTaxAmount(safe(o.getTotalTaxAmount()))
                    .totalDiscountAmount(safe(o.getTotalDiscountAmount()))
                    .grandTotal(safe(o.getGrandTotal()))
                    .orderDate(od != null ? LocalDateTime.ofInstant(od, IST) : null)
                    .createdAt(o.getCreatedAt())
                    .lines(lines)
                    .build();
        }).collect(Collectors.toList());
    }

    // ─── Unified Sales + Invoices ───────────────────────────────────────────

    public List<SalesInvoiceReportDto> getSalesInvoices(Instant from, Instant to, String filterType) {
        List<SalesInvoiceReportDto> rows = new ArrayList<>();
        Set<UUID> includedOrderIds = new HashSet<>();

        for (Order order : fetchSaleOrders(from, to)) {
            includedOrderIds.add(order.getId());
            Invoice invoice = selectDisplayInvoice(invoiceRepository.findByOrderId(order.getId()));
            Payment payment = latestPayment(paymentRepository.findByOrderId(order.getId()));
            SalesInvoiceReportDto row = buildSalesInvoiceRow(order, invoice, payment, true);
            if (matchesSalesInvoiceFilter(row, filterType)) {
                rows.add(row);
            }
        }

        for (Invoice invoice : fetchCustomerInvoices(from, to)) {
            UUID orderId = invoice.getOrderId();
            if (orderId != null && includedOrderIds.contains(orderId)) {
                continue;
            }

            Optional<Order> linkedOrder = orderId != null ? orderRepository.findById(orderId) : Optional.empty();
            if (linkedOrder.isPresent() && linkedOrder.get().getOrderType() != OrderType.SALE) {
                continue;
            }

            Payment payment = orderId != null ? latestPayment(paymentRepository.findByOrderId(orderId)) : null;
            SalesInvoiceReportDto row = buildSalesInvoiceRow(linkedOrder.orElse(null), invoice, payment, false);
            if (matchesSalesInvoiceFilter(row, filterType)) {
                rows.add(row);
            }
        }

        List<SalesInvoiceReportDto> sortedRows = rows.stream()
                .sorted(Comparator.comparing(
                        SalesInvoiceReportDto::getTransactionDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        attachBranchDetails(sortedRows);
        return sortedRows;
    }

    // ─── Item-wise Sales ────────────────────────────────────────────────────

    public List<ItemSalesDto> getItemWiseSales(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        Map<String, BigDecimal[]> itemMap = new LinkedHashMap<>();

        for (Order o : orders) {
            if (o.getLines() == null) continue;
            for (OrderLine line : o.getLines()) {
                if (!line.isActive()) continue;
                String name = line.getProductName() != null ? line.getProductName() : "Unknown Item";
                String cat = line.getCategoryName() != null ? line.getCategoryName() : "Uncategorized";
                String key = name + "|||" + cat;

                itemMap.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                BigDecimal[] vals = itemMap.get(key);
                vals[0] = vals[0].add(safe(line.getQuantity()));
                vals[1] = vals[1].add(safe(line.getLineTotal()));
            }
        }

        return itemMap.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|\\|\\|", 2);
                    return ItemSalesDto.builder()
                            .productName(parts[0])
                            .categoryName(parts.length > 1 ? parts[1] : "Uncategorized")
                            .quantitySold(e.getValue()[0])
                            .revenue(e.getValue()[1])
                            .build();
                })
                .sorted((a, b) -> b.getRevenue().compareTo(a.getRevenue()))
                .collect(Collectors.toList());
    }

    // ─── Payment Breakdown ──────────────────────────────────────────────────

    public List<PaymentBreakdownDto> getPaymentBreakdown(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        Map<String, BigDecimal[]> payMethodMap = new LinkedHashMap<>();
        Map<UUID, List<Payment>> paymentsByOrder = new LinkedHashMap<>();
        List<Payment> activePayments = new ArrayList<>();

        for (Order o : orders) {
            totalRevenue = totalRevenue.add(safe(o.getGrandTotal()));
            List<Payment> payments = paymentRepository.findByOrderId(o.getId()).stream()
                    .filter(this::isActivePayment)
                    .collect(Collectors.toList());
            paymentsByOrder.put(o.getId(), payments);
            activePayments.addAll(payments);
        }

        Set<UUID> paymentIds = activePayments.stream()
                .map(Payment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<PaymentSplit>> splitsByPaymentId = paymentIds.isEmpty()
                ? Map.of()
                : paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds).stream()
                        .collect(Collectors.groupingBy(PaymentSplit::getPaymentId, LinkedHashMap::new, Collectors.toList()));

        for (Order o : orders) {
            List<Payment> payments = paymentsByOrder.getOrDefault(o.getId(), List.of());
            if (payments.isEmpty()) {
                addPaymentBreakdownBucket(payMethodMap, "UNASSIGNED", safe(o.getGrandTotal()));
            } else {
                for (Payment p : payments) {
                    List<PaymentSplit> splits = splitsByPaymentId.getOrDefault(p.getId(), List.of());
                    if (splits.isEmpty()) {
                        addPaymentBreakdownBucket(payMethodMap, normalizeReportPaymentMethod(p.getPaymentMethod()), safe(p.getAmountPaid()));
                    } else {
                        for (PaymentSplit split : splits) {
                            addPaymentBreakdownBucket(payMethodMap, normalizeReportPaymentMethod(split.getPaymentMethod()), safe(split.getAmount()));
                        }
                    }
                }
            }
        }

        BigDecimal finalTotal = totalRevenue;
        return payMethodMap.entrySet().stream()
                .map(e -> PaymentBreakdownDto.builder()
                        .paymentMethod(e.getKey())
                        .orderCount(e.getValue()[0].longValue())
                        .totalAmount(e.getValue()[1])
                        .percentage(finalTotal.compareTo(BigDecimal.ZERO) > 0
                                ? e.getValue()[1].multiply(BigDecimal.valueOf(100)).divide(finalTotal, 1, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO)
                        .build())
                .sorted((a, b) -> b.getTotalAmount().compareTo(a.getTotalAmount()))
                .collect(Collectors.toList());
    }

    // ─── Tax Summary ────────────────────────────────────────────────────────

    public List<TaxSummaryDto> getTaxSummary(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        Map<BigDecimal, BigDecimal[]> taxMap = new TreeMap<>();

        for (Order o : orders) {
            if (o.getLines() == null) continue;
            for (OrderLine line : o.getLines()) {
                if (!line.isActive()) continue;
                BigDecimal rate = safe(line.getTaxRate());
                BigDecimal taxable = safe(line.getLineTotal()).subtract(safe(line.getTaxAmount()));
                BigDecimal tax = safe(line.getTaxAmount());

                taxMap.computeIfAbsent(rate, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                BigDecimal[] vals = taxMap.get(rate);
                vals[0] = vals[0].add(taxable);
                vals[1] = vals[1].add(tax);
                vals[2] = vals[2].add(BigDecimal.ONE);
            }
        }

        return taxMap.entrySet().stream()
                .map(e -> {
                    BigDecimal halfTax = e.getValue()[1].divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    return TaxSummaryDto.builder()
                            .taxRate(e.getKey())
                            .taxableAmount(e.getValue()[0])
                            .cgst(halfTax)
                            .sgst(halfTax)
                            .totalTax(e.getValue()[1])
                            .lineCount(e.getValue()[2].longValue())
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─── Hourly Sales ───────────────────────────────────────────────────────

    public List<HourlySalesDto> getHourlySales(Instant from, Instant to) {
        List<Order> orders = fetchSaleOrders(from, to);

        Map<Integer, BigDecimal[]> hourlyMap = new TreeMap<>();

        for (Order o : orders) {
            Instant orderTime = o.getOrderDate() != null ? o.getOrderDate() : o.getCreatedAt().atZone(IST).toInstant();
            int hour = LocalDateTime.ofInstant(orderTime, IST).getHour();
            hourlyMap.computeIfAbsent(hour, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] vals = hourlyMap.get(hour);
            vals[0] = vals[0].add(BigDecimal.ONE);
            vals[1] = vals[1].add(safe(o.getGrandTotal()));
        }

        return hourlyMap.entrySet().stream()
                .map(e -> HourlySalesDto.builder()
                        .hour(e.getKey())
                        .hourLabel(String.format("%02d:00", e.getKey()))
                        .orderCount(e.getValue()[0].longValue())
                        .totalAmount(e.getValue()[1])
                        .build())
                .collect(Collectors.toList());
    }

    // ─── Invoices (with filter) ─────────────────────────────────────────────

    public List<InvoiceReportDto> getInvoices(Instant from, Instant to, String filterType) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = reportOrgId();

        // invoiceDate is LocalDateTime — convert Instant to LocalDateTime for comparison
        LocalDateTime ldFrom = from != null ? LocalDateTime.ofInstant(from, IST) : null;
        LocalDateTime ldTo = to != null ? LocalDateTime.ofInstant(to, IST) : null;

        List<Invoice> allInvoices = invoiceRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (ldFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), ldFrom));
            }
            if (ldTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), ldTo));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        // Apply filter
        List<Invoice> filtered;
        String ft = filterType != null ? filterType.toUpperCase() : "ALL";
        if ("PAID".equals(ft)) {
            filtered = allInvoices.stream()
                    .filter(i -> "PAID".equalsIgnoreCase(i.getStatus()) && !Boolean.TRUE.equals(i.getIsCredit()))
                    .collect(Collectors.toList());
        } else if ("CREDIT".equals(ft)) {
            filtered = allInvoices.stream()
                    .filter(i -> Boolean.TRUE.equals(i.getIsCredit()) || "UNPAID".equalsIgnoreCase(i.getStatus()))
                    .filter(i -> !"VOID".equalsIgnoreCase(i.getStatus()))
                    .collect(Collectors.toList());
        } else if ("VOIDED".equals(ft)) {
            filtered = allInvoices.stream()
                    .filter(i -> "VOID".equalsIgnoreCase(i.getStatus()))
                    .collect(Collectors.toList());
        } else {
            filtered = allInvoices;
        }

        return filtered.stream().map(inv -> {
            String paymentMethod = null;
            String paymentNo = null;
            String customerName = null;

            if (inv.getOrderId() != null) {
                List<Payment> payments = paymentRepository.findByOrderId(inv.getOrderId());
                if (!payments.isEmpty()) {
                    Payment latest = payments.get(payments.size() - 1);
                    paymentMethod = latest.getPaymentMethod();
                    paymentNo = latest.getReferenceNo();
                }
                orderRepository.findById(inv.getOrderId()).ifPresent(order -> {
                    // can't assign to local var directly in lambda, handled below
                });
                Optional<Order> linkedOrder = orderRepository.findById(inv.getOrderId());
                if (linkedOrder.isPresent()) {
                    customerName = customerDisplay(linkedCustomers(linkedOrder.get()));
                }
            }

            return InvoiceReportDto.builder()
                    .id(inv.getId())
                    .orderId(inv.getOrderId())
                    .invoiceNo(inv.getInvoiceNo())
                    .invoiceType(inv.getInvoiceType() != null ? inv.getInvoiceType().name() : null)
                    .status(inv.getStatus())
                    .docStatus(inv.getDocStatus())
                    .totalAmount(safe(inv.getTotalAmount()))
                    .amountDue(safe(inv.getAmountDue()))
                    .customerName(customerName)
                    .paymentMethod(paymentMethod)
                    .paymentNo(paymentNo)
                    .invoiceDate(inv.getInvoiceDate())
                    .description(inv.getDescription())
                    .reference(inv.getReference())
                    .originalInvoiceId(inv.getOriginalInvoiceId())
                    .build();
        }).collect(Collectors.toList());
    }

    // ─── Profit & Loss ──────────────────────────────────────────────────────

    public ProfitLossDto getProfitLoss(Instant from, Instant to) {
        LocalDateTime ldFrom = from != null ? LocalDateTime.ofInstant(from, IST) : null;
        LocalDateTime ldTo = to != null ? LocalDateTime.ofInstant(to, IST) : null;
        AccountingSummaryDto summary = accountingService.getSummary(ldFrom, ldTo);
        BigDecimal cashCollectedAfterExpenses = safe(summary.getPaymentCollected())
                .subtract(safe(summary.getExpenses()))
                .subtract(safe(summary.getCogsPurchases()));

        return ProfitLossDto.builder()
                .grossSales(safe(summary.getGrossSales()))
                .discounts(safe(summary.getDiscounts()))
                .netSales(safe(summary.getNetSales()))
                .totalTax(safe(summary.getOutputTax()))
                .inputTax(safe(summary.getInputTax()))
                .cogsPurchases(safe(summary.getCogsPurchases()))
                .operatingExpenses(safe(summary.getExpenses()))
                .totalExpenses(safe(summary.getExpenses()).add(safe(summary.getCogsPurchases())))
                .netProfit(safe(summary.getProfit()))
                .creditOutstanding(safe(summary.getReceivable()))
                .netCashProfit(cashCollectedAfterExpenses)
                .cashCollectedAfterExpenses(cashCollectedAfterExpenses)
                .basis("ACCOUNTING_JOURNALS")
                .build();
    }

    // ─── Void Invoice ───────────────────────────────────────────────────────

    @Transactional
    public Invoice voidInvoice(UUID invoiceId, String reason) {
        UUID clientId = TenantContext.getCurrentTenant();
        Invoice invoice;
        if (SecurityUtils.isSuperAdmin()) {
            invoice = invoiceRepository.findByIdAndClientId(invoiceId, clientId)
                    .orElseThrow(() -> new com.restaurant.pos.common.exception.ResourceNotFoundException("Invoice not found"));
        } else {
            invoice = invoiceRepository.findByIdAndClientIdAndOrgId(invoiceId, clientId, TenantContext.getCurrentOrg())
                    .orElseThrow(() -> new com.restaurant.pos.common.exception.ResourceNotFoundException("Invoice not found"));
        }

        if ("VOID".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalStateException("Invoice is already voided");
        }

        if (invoice.getOrderId() != null) {
            Optional<Order> linkedOrder = orderRepository.findById(invoice.getOrderId());
            if (linkedOrder.isPresent() && isSaleOrder(linkedOrder.get())) {
                voidSaleFinancialChain(linkedOrder.get(), reason);
                return invoiceRepository.findById(invoiceId).orElse(invoice);
            }
        }

        accountingPostingService.reverseInvoice(invoice, "Invoice voided");
        markInvoiceVoided(invoice, reason);
        return invoiceRepository.save(invoice);
    }

    // ─── Private Helpers ────────────────────────────────────────────────────

    private SalesInvoiceReportDto buildSalesInvoiceRow(Order order, Invoice invoice, Payment payment, boolean preferOrderDate) {
        LocalDateTime orderDate = order != null && order.getOrderDate() != null
                ? LocalDateTime.ofInstant(order.getOrderDate(), IST)
                : null;
        LocalDateTime invoiceDate = invoice != null ? invoice.getInvoiceDate() : null;
        LocalDateTime createdAt = order != null ? order.getCreatedAt() : (invoice != null ? invoice.getCreatedAt() : null);
        LocalDateTime transactionDate = preferOrderDate
                ? firstDate(orderDate, invoiceDate, createdAt)
                : firstDate(invoiceDate, orderDate, createdAt);

        UUID orderId = order != null ? order.getId() : (invoice != null ? invoice.getOrderId() : null);
        UUID invoiceId = invoice != null ? invoice.getId() : null;
        String id = orderId != null ? orderId.toString() : (invoiceId != null ? invoiceId.toString() : UUID.randomUUID().toString());

        List<OrderCustomerDto> customers = linkedCustomers(order);

        return SalesInvoiceReportDto.builder()
                .id(id)
                .orderId(orderId)
                .invoiceId(invoiceId)
                .branchId(resolveBranchId(order, invoice))
                .orderNo(order != null ? order.getOrderNo() : null)
                .invoiceNo(invoice != null ? invoice.getInvoiceNo() : null)
                .paymentNo(payment != null ? payment.getReferenceNo() : (order != null ? order.getPaymentNo() : null))
                .orderStatus(order != null ? order.getOrderStatus() : null)
                .paymentStatus(order != null ? order.getPaymentStatus() : null)
                .invoiceStatus(invoice != null ? invoice.getStatus() : null)
                .invoiceDocStatus(invoice != null ? invoice.getDocStatus() : null)
                .fulfillmentType(order != null ? order.getFulfillmentType() : null)
                .tableNumber(order != null ? order.getTableNumber() : null)
                .customerName(customerDisplay(customers))
                .customerPhone(customerPhoneDisplay(customers))
                .customers(customers)
                .paymentMethod(payment != null ? payment.getPaymentMethod() : null)
                .totalAmount(order != null ? safe(order.getTotalAmount()) : (invoice != null ? safe(invoice.getTotalAmount()) : BigDecimal.ZERO))
                .totalTaxAmount(order != null ? safe(order.getTotalTaxAmount()) : BigDecimal.ZERO)
                .totalDiscountAmount(order != null ? safe(order.getTotalDiscountAmount()) : BigDecimal.ZERO)
                .grandTotal(order != null ? safe(order.getGrandTotal()) : (invoice != null ? safe(invoice.getTotalAmount()) : BigDecimal.ZERO))
                .amountDue(invoice != null ? safe(invoice.getAmountDue()) : null)
                .transactionDate(transactionDate)
                .orderDate(orderDate)
                .invoiceDate(invoiceDate)
                .createdAt(createdAt)
                .voidable(invoice != null && !isVoidStatus(invoice.getStatus()) && !isVoidStatus(invoice.getDocStatus()))
                .lines(toSalesInvoiceLines(order))
                .build();
    }

    private UUID resolveBranchId(Order order, Invoice invoice) {
        if (order != null && order.getOrgId() != null) {
            return order.getOrgId();
        }
        return invoice != null ? invoice.getOrgId() : null;
    }

    private void attachBranchDetails(List<SalesInvoiceReportDto> rows) {
        Set<UUID> branchIds = rows.stream()
                .map(SalesInvoiceReportDto::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (branchIds.isEmpty()) {
            return;
        }

        UUID clientId = TenantContext.getCurrentTenant();
        Map<UUID, Organization> branchesById = organizationRepository
                .findAllByClientIdAndIdIn(clientId, branchIds)
                .stream()
                .collect(Collectors.toMap(Organization::getId, branch -> branch, (left, right) -> left));

        rows.forEach(row -> {
            Organization branch = branchesById.get(row.getBranchId());
            if (branch != null) {
                row.setBranchName(branch.getName());
                row.setBranchCode(branch.getBranchCode());
            }
        });
    }

    private List<SalesInvoiceReportDto.LineDto> toSalesInvoiceLines(Order order) {
        if (order == null || order.getLines() == null) {
            return List.of();
        }
        return order.getLines().stream()
                .filter(OrderLine::isActive)
                .map(line -> SalesInvoiceReportDto.LineDto.builder()
                        .productId(line.getProductId())
                        .productName(line.getProductName())
                        .categoryName(line.getCategoryName())
                        .quantity(safe(line.getQuantity()))
                        .unitPrice(safe(line.getUnitPrice()))
                        .taxRate(safe(line.getTaxRate()))
                        .taxAmount(safe(line.getTaxAmount()))
                        .discountAmount(safe(line.getDiscountAmount()))
                        .lineTotal(safe(line.getLineTotal()))
                        .build())
                .collect(Collectors.toList());
    }

    private List<OrderCustomerDto> linkedCustomers(Order order) {
        if (order == null || order.getId() == null || order.getClientId() == null) {
            return List.of();
        }
        List<Customer> customers = new ArrayList<>(customerRepository.findByClientIdAndOrderLink(
                order.getClientId(),
                orderNeedle(order.getId(), false),
                orderNeedle(order.getId(), true)
        ));
        if (customers.isEmpty() && order.getCustomerId() != null) {
            customerRepository.findByIdAndClientId(order.getCustomerId(), order.getClientId()).ifPresent(customers::add);
        }
        List<OrderCustomerDto> result = new ArrayList<>();
        for (int i = 0; i < customers.size(); i++) {
            Customer customer = customers.get(i);
            result.add(OrderCustomerDto.builder()
                    .id(customer.getId())
                    .name(customer.getName())
                    .phone(customer.getPhone())
                    .primary(isPrimaryForOrder(customer, order.getId()) || i == 0)
                    .build());
        }
        result.sort((a, b) -> Boolean.compare(!a.isPrimary(), !b.isPrimary()));
        return result;
    }

    private String customerDisplay(List<OrderCustomerDto> customers) {
        if (customers == null || customers.isEmpty()) {
            return null;
        }
        return customers.stream()
                .map(customer -> {
                    String name = customer.getName() != null && !customer.getName().isBlank() ? customer.getName() : "Guest";
                    return customer.getPhone() != null && !customer.getPhone().isBlank()
                            ? name + " (" + customer.getPhone() + ")"
                            : name;
                })
                .collect(Collectors.joining(", "));
    }

    private String customerPhoneDisplay(List<OrderCustomerDto> customers) {
        if (customers == null || customers.isEmpty()) {
            return null;
        }
        return customers.stream()
                .map(OrderCustomerDto::getPhone)
                .filter(phone -> phone != null && !phone.isBlank())
                .collect(Collectors.joining(", "));
    }

    private boolean isPrimaryForOrder(Customer customer, UUID orderId) {
        if (orderId == null || customer.getOrderLinks() == null) {
            return false;
        }
        return customer.getOrderLinks().stream()
                .anyMatch(link -> Objects.equals(orderId, link.getOrderId()) && Boolean.TRUE.equals(link.getIsPrimary()));
    }

    private String orderNeedle(UUID orderId, boolean primary) {
        if (primary) {
            return "[{\"orderId\":\"" + orderId + "\",\"isPrimary\":true}]";
        }
        return "[{\"orderId\":\"" + orderId + "\"}]";
    }

    private Invoice selectDisplayInvoice(List<Invoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return null;
        }
        return invoices.stream()
                .filter(this::isCustomerInvoice)
                .max(Comparator
                        .comparing((Invoice invoice) -> !isVoidStatus(invoice.getStatus()) && !isVoidStatus(invoice.getDocStatus()))
                        .thenComparing(this::invoiceSortDate))
                .orElse(null);
    }

    private Payment latestPayment(List<Payment> payments) {
        if (payments == null || payments.isEmpty()) {
            return null;
        }
        return payments.stream()
                .max(Comparator.comparing(this::paymentSortDate))
                .orElse(null);
    }

    private void voidSaleFinancialChain(Order order, String reason) {
        order.setOrderStatus("CANCELLED");
        order.setPaymentStatus("VOID");
        order.setDescription("Voided via invoice: " + (reason != null ? reason : "No reason provided"));
        orderRepository.save(order);

        invoiceRepository.findByOrderId(order.getId()).forEach(linkedInvoice -> {
            if (!isVoidStatus(linkedInvoice.getStatus()) && !isVoidStatus(linkedInvoice.getDocStatus())) {
                accountingPostingService.reverseInvoice(linkedInvoice, "Invoice voided");
            }
            markInvoiceVoided(linkedInvoice, reason);
            invoiceRepository.save(linkedInvoice);
        });

        paymentRepository.findByOrderId(order.getId()).forEach(payment -> {
            if (isActivePayment(payment)) {
                accountingPostingService.reversePayment(payment, "Invoice voided");
            }
            payment.setDocStatus("VOIDED");
            payment.setIsactive("N");
            paymentRepository.save(payment);
        });

        accountingPostingService.reverseSaleCogs(order, "Invoice voided");
    }

    private void markInvoiceVoided(Invoice invoice, String reason) {
        invoice.setStatus("VOID");
        invoice.setDocStatus("VOIDED");
        invoice.setIsPaid(false);
        invoice.setAmountDue(BigDecimal.ZERO);
        if (reason != null && !reason.isBlank()) {
            invoice.setDescription("void: " + reason + (invoice.getDescription() != null ? " | " + invoice.getDescription() : ""));
        }
    }

    private boolean isSaleOrder(Order order) {
        return order != null && (order.getOrderType() == null || order.getOrderType() == OrderType.SALE);
    }

    private boolean matchesSalesInvoiceFilter(SalesInvoiceReportDto row, String filterType) {
        String ft = filterType != null ? filterType.toUpperCase(Locale.ROOT) : "ALL";
        boolean isVoided = isVoidStatus(row.getInvoiceStatus()) || isVoidStatus(row.getInvoiceDocStatus()) || isVoidStatus(row.getOrderStatus());
        BigDecimal due = row.getAmountDue();
        boolean hasDue = due != null && due.compareTo(BigDecimal.ZERO) > 0;
        boolean invoicePaid = "PAID".equalsIgnoreCase(row.getInvoiceStatus()) && !hasDue;
        boolean orderPaid = "PAID".equalsIgnoreCase(row.getPaymentStatus());
        boolean invoiceCredit = "UNPAID".equalsIgnoreCase(row.getInvoiceStatus())
                || "PARTIAL".equalsIgnoreCase(row.getInvoiceStatus())
                || hasDue;
        boolean orderCredit = row.getInvoiceId() == null
                && ("PENDING".equalsIgnoreCase(row.getPaymentStatus()) || "PARTIAL".equalsIgnoreCase(row.getPaymentStatus()));

        if ("PAID".equals(ft)) {
            return !isVoided && (invoicePaid || orderPaid);
        }
        if ("CREDIT".equals(ft)) {
            return !isVoided && (invoiceCredit || orderCredit);
        }
        if ("VOIDED".equals(ft)) {
            return isVoided;
        }
        return true;
    }

    private boolean isCustomerInvoice(Invoice invoice) {
        return invoice != null && invoice.getInvoiceType() == InvoiceType.CUSTOMER_INVOICE;
    }

    private boolean isVoidStatus(String status) {
        return status != null && ("VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status));
    }

    private LocalDateTime invoiceSortDate(Invoice invoice) {
        return firstDate(invoice.getInvoiceDate(), invoice.getCreatedAt(), LocalDateTime.MIN);
    }

    private LocalDateTime paymentSortDate(Payment payment) {
        return firstDate(payment.getPaymentDate(), payment.getCreatedAt(), LocalDateTime.MIN);
    }

    private LocalDateTime firstDate(LocalDateTime... dates) {
        for (LocalDateTime date : dates) {
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    private List<Invoice> fetchCustomerInvoices(Instant from, Instant to) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = reportOrgId();
        LocalDateTime ldFrom = from != null ? LocalDateTime.ofInstant(from, IST) : null;
        LocalDateTime ldTo = to != null ? LocalDateTime.ofInstant(to, IST) : null;

        return invoiceRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
            if (ldFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), ldFrom));
            }
            if (ldTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), ldTo));
            }
            query.orderBy(cb.desc(root.get("invoiceDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private List<Order> fetchSaleOrders(Instant from, Instant to) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = reportOrgId();

        return orderRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.SALE));
            predicates.add(cb.equal(root.get("orderStatus"), "COMPLETED"));
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            // orderDate is Instant — compare directly with Instant params
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

    private UUID reportOrgId() {
        return TenantContext.getCurrentOrg();
    }

    private boolean isActivePayment(Payment payment) {
        return payment != null
                && !"N".equalsIgnoreCase(payment.getIsactive())
                && !isVoidStatus(payment.getDocStatus());
    }

    private void addPaymentBreakdownBucket(Map<String, BigDecimal[]> payMethodMap, String paymentMethod, BigDecimal amount) {
        BigDecimal value = safe(amount);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String method = normalizeReportPaymentMethod(paymentMethod);
        payMethodMap.computeIfAbsent(method, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        payMethodMap.get(method)[0] = payMethodMap.get(method)[0].add(BigDecimal.ONE);
        payMethodMap.get(method)[1] = payMethodMap.get(method)[1].add(value);
    }

    private String normalizeReportPaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "UNASSIGNED";
        }
        String method = paymentMethod.trim().toUpperCase(Locale.ROOT);
        if (Set.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE").contains(method)) {
            return method;
        }
        return "UNASSIGNED";
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
