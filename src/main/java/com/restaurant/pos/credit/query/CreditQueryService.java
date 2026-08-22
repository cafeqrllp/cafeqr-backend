package com.restaurant.pos.credit.query;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.common.CreditGuard;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.dto.CreditOrderDto;
import com.restaurant.pos.credit.dto.CreditReportDto;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchasing.domain.Vendor;
import com.restaurant.pos.purchasing.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CQRS Query Service for the Credit module.
 * Handles all read-only operations for both Customers and Vendors:
 * list partners, orders, payments, report.
 */
@Service
@RequiredArgsConstructor
public class CreditQueryService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "id", "createdAt", "updatedAt", "invoiceDate", "paymentDate", "invoiceNo", "referenceNo", "totalAmount", "amountPaid"
    );

    private final CreditGuard creditGuard;
    private final CreditCustomerRepository creditCustomerRepository;
    private final VendorRepository vendorRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final com.restaurant.pos.order.repository.OrderRepository orderRepository;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    // ── List Customers / Partners ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CreditBPartnerDto> listCustomers(String status) {
        return listPartners(status, "CUSTOMER");
    }

    @Transactional(readOnly = true)
    public List<CreditBPartnerDto> listPartners(String status, String partnerType) {
        validatePartnerType(partnerType);
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        if (creditGuard.isVendor(partnerType)) {
            List<Vendor> vendors = vendorRepository.findByClientIdOrderByNameAsc(clientId);
            if (vendors.isEmpty()) return List.of();

            List<UUID> vendorIds = vendors.stream().map(Vendor::getId).collect(Collectors.toList());
            Map<UUID, BigDecimal> invoiceTotals = toMapBigDecimal(invoiceRepository.sumTotalAmountByVendorIds(clientId, vendorIds));
            Map<UUID, BigDecimal> pendingBalances = toMapBigDecimal(invoiceRepository.sumAmountDueByVendorIds(clientId, vendorIds));
            Map<UUID, BigDecimal> paidTotals = toMapBigDecimal(paymentRepository.sumPaidByVendorIds(clientId, vendorIds));
            Map<UUID, Long> openCounts = toMapLong(invoiceRepository.countOpenInvoicesByVendorIds(clientId, vendorIds));

            return vendors.stream().map(vendor -> {
                BigDecimal invTotal = creditGuard.money(invoiceTotals.get(vendor.getId()));
                BigDecimal pendingBal = creditGuard.money(pendingBalances.get(vendor.getId()));
                BigDecimal paid = creditGuard.money(paidTotals.get(vendor.getId()));
                long openCount = openCounts.getOrDefault(vendor.getId(), 0L);

                BigDecimal totalCredit = creditGuard.money(vendor.getOpeningBalance()).add(invTotal);
                BigDecimal balance = creditGuard.money(vendor.getOpeningBalance()).add(pendingBal).setScale(2, RoundingMode.HALF_UP);
                String vStatus = "Y".equalsIgnoreCase(vendor.getIsactive()) ? "ACTIVE" : "INACTIVE";

                return CreditBPartnerDto.builder()
                        .id(vendor.getId())
                        .name(vendor.getName())
                        .phone(vendor.getPhone())
                        .email(vendor.getEmail())
                        .status(vStatus)
                        .creditLimit(creditGuard.money(vendor.getCreditLimit()))
                        .openingBalance(creditGuard.money(vendor.getOpeningBalance()))
                        .totalCreditExtended(totalCredit)
                        .paymentsReceived(paid)
                        .balance(balance)
                        .openInvoiceCount(openCount)
                        .notes(vendor.getAddress())
                        .partnerType("VENDOR")
                        .build();
            }).collect(Collectors.toList());
        }

        List<CreditCustomer> customers = status != null && !status.isBlank()
                ? creditCustomerRepository.findByClientIdAndStatusAndIsactiveOrderByNameAsc(clientId, creditGuard.normalizeStatus(status), "Y")
                : creditCustomerRepository.findByClientIdAndIsactiveOrderByNameAsc(clientId, "Y");

        if (customers.isEmpty()) return List.of();

        List<UUID> customerIds = customers.stream().map(CreditCustomer::getId).collect(Collectors.toList());
        Map<UUID, BigDecimal> invoiceTotals = toMapBigDecimal(invoiceRepository.sumTotalAmountByCustomerIds(clientId, customerIds));
        Map<UUID, BigDecimal> pendingBalances = toMapBigDecimal(invoiceRepository.sumAmountDueByCustomerIds(clientId, customerIds));
        Map<UUID, BigDecimal> paidTotals = toMapBigDecimal(paymentRepository.sumPaidByCustomerIds(clientId, customerIds));
        Map<UUID, Long> openCounts = toMapLong(invoiceRepository.countOpenInvoicesByCustomerIds(clientId, customerIds));

        return customers.stream().map(customer -> {
            BigDecimal invTotal = creditGuard.money(invoiceTotals.get(customer.getId()));
            BigDecimal pendingBal = creditGuard.money(pendingBalances.get(customer.getId()));
            BigDecimal paid = creditGuard.money(paidTotals.get(customer.getId()));
            long openCount = openCounts.getOrDefault(customer.getId(), 0L);

            BigDecimal totalCredit = creditGuard.money(customer.getOpeningBalance()).add(invTotal);
            BigDecimal balance = creditGuard.money(customer.getOpeningBalance()).add(pendingBal).setScale(2, RoundingMode.HALF_UP);

            return CreditBPartnerDto.builder()
                    .id(customer.getId())
                    .linkedCustomerId(customer.getLinkedCustomerId())
                    .name(customer.getName())
                    .phone(customer.getPhone())
                    .email(customer.getEmail())
                    .status(customer.getStatus())
                    .creditLimit(creditGuard.money(customer.getCreditLimit()))
                    .openingBalance(creditGuard.money(customer.getOpeningBalance()))
                    .totalCreditExtended(totalCredit)
                    .paymentsReceived(paid)
                    .balance(balance)
                    .openInvoiceCount(openCount)
                    .notes(customer.getNotes())
                    .partnerType("CUSTOMER")
                    .build();
        }).collect(Collectors.toList());
    }

    // ── Customer / Vendor Orders ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CreditOrderDto> getCustomerOrders(UUID partnerId, Pageable pageable) {
        return getPartnerOrders(partnerId, pageable, "CUSTOMER");
    }

    @Transactional(readOnly = true)
    public Page<CreditOrderDto> getPartnerOrders(UUID partnerId, Pageable pageable, String partnerType) {
        validatePartnerType(partnerType);
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        Pageable defaultSortedPageable = ensureSort(pageable, "invoiceDate", Sort.Direction.DESC);

        if (creditGuard.isVendor(partnerType)) {
            Vendor vendor = creditGuard.getVendor(partnerId, clientId);
            Specification<Invoice> spec = (root, query, cb) -> {
                var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
                predicates.add(cb.equal(root.get("clientId"), clientId));
                predicates.add(cb.or(
                        cb.equal(root.get("vendorId"), vendor.getId()),
                        cb.equal(root.get("creditCustomerId"), vendor.getId())
                ));
                predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.VENDOR_BILL));
                predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
                predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED", "PAID")));
                predicates.add(cb.gt(root.get("amountDue"), BigDecimal.ZERO));
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            };

            Page<Invoice> invoicePage = invoiceRepository.findAll(spec, defaultSortedPageable);
            Map<UUID, Order> ordersById = batchLoadOrdersForInvoices(invoicePage.getContent());
            return invoicePage.map(invoice -> toVendorOrderDto(invoice, vendor, ordersById.get(invoice.getOrderId())));
        }

        CreditCustomer customer = creditGuard.getCreditCustomer(partnerId, clientId);
        Specification<Invoice> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            predicates.add(cb.equal(root.get("creditCustomerId"), customer.getId()));
            predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED", "PAID")));
            predicates.add(cb.gt(root.get("amountDue"), BigDecimal.ZERO));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Invoice> invoicePage = invoiceRepository.findAll(spec, defaultSortedPageable);
        Map<UUID, Order> ordersById = batchLoadOrdersForInvoices(invoicePage.getContent());
        return invoicePage.map(invoice -> toOrderDto(invoice, customer, ordersById.get(invoice.getOrderId())));
    }

    // ── Customer / Vendor Payments ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CreditReportDto.PaymentTransactionDto> getCustomerPayments(UUID partnerId, Pageable pageable) {
        return getPartnerPayments(partnerId, pageable, "CUSTOMER");
    }

    @Transactional(readOnly = true)
    public Page<CreditReportDto.PaymentTransactionDto> getPartnerPayments(UUID partnerId, Pageable pageable, String partnerType) {
        validatePartnerType(partnerType);
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        Pageable defaultSortedPageable = ensureSort(pageable, "paymentDate", Sort.Direction.DESC);

        if (creditGuard.isVendor(partnerType)) {
            Vendor vendor = creditGuard.getVendor(partnerId, clientId);
            Specification<Payment> spec = (root, query, cb) -> {
                var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
                predicates.add(cb.equal(root.get("clientId"), clientId));
                predicates.add(cb.equal(root.get("paymentType"), PaymentType.OUTBOUND));
                predicates.add(cb.equal(root.get("creditCustomerId"), vendor.getId()));
                predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
                predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.not(cb.upper(root.get("docStatus").as(String.class)).in("VOID", "VOIDED"))));
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            };

            Page<Payment> paymentPage = paymentRepository.findAll(spec, defaultSortedPageable);
            BatchPaymentData batchData = batchLoadForPayments(paymentPage.getContent());
            return paymentPage.map(payment -> toVendorPaymentTransaction(payment, vendor, batchData.ordersById(), batchData.invoicesById()));
        }

        CreditCustomer customer = creditGuard.getCreditCustomer(partnerId, clientId);
        Specification<Payment> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            predicates.add(cb.equal(root.get("creditCustomerId"), customer.getId()));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.not(cb.upper(root.get("docStatus").as(String.class)).in("VOID", "VOIDED"))));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Payment> paymentPage = paymentRepository.findAll(spec, defaultSortedPageable);
        BatchPaymentData batchData = batchLoadForPayments(paymentPage.getContent());
        return paymentPage.map(payment -> toPaymentTransaction(payment, customer, batchData.ordersById(), batchData.invoicesById()));
    }

    // ── Report ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditReportDto report(Instant from, Instant to) {
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("Start date ('from') cannot be after end date ('to')");
        }
        LocalDateTime fromDate = from != null ? LocalDateTime.ofInstant(from, zoneId) : null;
        LocalDateTime toDate = to != null ? LocalDateTime.ofInstant(to, zoneId) : null;
        if (to != null) {
            var zdt = to.atZone(zoneId);
            if (zdt.getHour() == 0 && zdt.getMinute() == 0 && zdt.getSecond() == 0 && zdt.getNano() == 0) {
                toDate = zdt.toLocalDate().atTime(23, 59, 59, 999999999);
            }
        }

        final LocalDateTime finalFromDate = fromDate;
        final LocalDateTime finalToDate = toDate;

        List<Invoice> invoices = invoiceRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
            predicates.add(cb.isNotNull(root.get("creditCustomerId")));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED")));
            if (finalFromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), finalFromDate));
            }
            if (finalToDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), finalToDate));
            }
            query.orderBy(cb.desc(root.get("invoiceDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        List<Payment> payments = paymentRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.isNotNull(root.get("creditCustomerId")));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.not(cb.upper(root.get("docStatus").as(String.class)).in("VOID", "VOIDED"))));
            if (finalFromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), finalFromDate));
            }
            if (finalToDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), finalToDate));
            }
            query.orderBy(cb.desc(root.get("paymentDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        Map<UUID, CreditCustomer> customersById = customersById(invoices, payments);
        Map<UUID, Order> ordersById = batchLoadOrdersForInvoices(invoices);

        BigDecimal creditExtended = invoices.stream().map(Invoice::getTotalAmount).map(creditGuard::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paymentsReceived = payments.stream().map(Payment::getAmountPaid).map(creditGuard::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outputTax = invoices.stream()
                .map(invoice -> ordersById.get(invoice.getOrderId()))
                .filter(Objects::nonNull)
                .map(Order::getTotalTaxAmount)
                .map(creditGuard::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CreditOrderDto> orderRows = invoices.stream()
                .map(invoice -> toOrderDto(invoice, customersById.get(invoice.getCreditCustomerId()), ordersById.get(invoice.getOrderId())))
                .collect(Collectors.toList());

        BatchPaymentData batchPaymentData = batchLoadForPayments(payments);
        List<CreditReportDto.PaymentTransactionDto> paymentRows = payments.stream()
                .map(payment -> toPaymentTransaction(payment, customersById.get(payment.getCreditCustomerId()), batchPaymentData.ordersById(), batchPaymentData.invoicesById()))
                .collect(Collectors.toList());

        BigDecimal totalOpeningBalance = customersById.values().stream()
                .map(CreditCustomer::getOpeningBalance)
                .map(creditGuard::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal outstanding = totalOpeningBalance.add(creditExtended).subtract(paymentsReceived).setScale(2, RoundingMode.HALF_UP);

        long customerCount = customersById.keySet().size();
        return CreditReportDto.builder()
                .creditExtended(creditExtended)
                .paymentsReceived(paymentsReceived)
                .outstanding(outstanding)
                .outputTax(outputTax)
                .orderCount((long) invoices.size())
                .customerCount(customerCount)
                .orders(orderRows)
                .payments(paymentRows)
                .build();
    }

    // ── Configuration check ──────────────────────────────────────────────────

    public boolean isCreditEnabled() {
        return creditGuard.isCreditEnabled();
    }

    // ── DTO Mappers & Helpers ─────────────────────────────────────────────────

    private void validatePartnerType(String partnerType) {
        if (partnerType == null || partnerType.isBlank()) return;
        String normalized = partnerType.trim().toUpperCase();
        if (!"CUSTOMER".equals(normalized) && !"VENDOR".equals(normalized)) {
            throw new BusinessException("Invalid partner type: " + partnerType + ". Must be CUSTOMER or VENDOR.");
        }
    }

    private Pageable ensureSort(Pageable pageable, String defaultSortProperty, Sort.Direction defaultDirection) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return PageRequest.of(
                    pageable != null ? pageable.getPageNumber() : 0,
                    pageable != null ? pageable.getPageSize() : 50,
                    Sort.by(defaultDirection, defaultSortProperty, "id")
            );
        }

        List<Sort.Order> sanitizedOrders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                sanitizedOrders.add(order);
            }
        }

        if (sanitizedOrders.isEmpty()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(defaultDirection, defaultSortProperty, "id"));
        }

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(sanitizedOrders));
    }

    private Map<UUID, BigDecimal> toMapBigDecimal(List<Object[]> results) {
        if (results == null || results.isEmpty()) return Map.of();
        Map<UUID, BigDecimal> map = new LinkedHashMap<>();
        for (Object[] row : results) {
            if (row != null && row.length >= 2 && row[0] instanceof UUID id) {
                BigDecimal val = row[1] instanceof BigDecimal bd ? bd : (row[1] instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO);
                map.put(id, val);
            }
        }
        return map;
    }

    private Map<UUID, Long> toMapLong(List<Object[]> results) {
        if (results == null || results.isEmpty()) return Map.of();
        Map<UUID, Long> map = new LinkedHashMap<>();
        for (Object[] row : results) {
            if (row != null && row.length >= 2 && row[0] instanceof UUID id) {
                Long val = row[1] instanceof Long l ? l : (row[1] instanceof Number n ? n.longValue() : 0L);
                map.put(id, val);
            }
        }
        return map;
    }

    private Map<UUID, Order> batchLoadOrdersForInvoices(List<Invoice> invoices) {
        if (invoices == null || invoices.isEmpty()) return Map.of();
        Set<UUID> orderIds = invoices.stream()
                .map(Invoice::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (orderIds.isEmpty()) return Map.of();
        return orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, order -> order, (left, right) -> left, LinkedHashMap::new));
    }

    private record BatchPaymentData(Map<UUID, Order> ordersById, Map<UUID, Invoice> invoicesById) {}

    private BatchPaymentData batchLoadForPayments(List<Payment> payments) {
        if (payments == null || payments.isEmpty()) return new BatchPaymentData(Map.of(), Map.of());

        Set<UUID> invoiceIds = payments.stream()
                .map(Payment::getInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Invoice> invoicesById = invoiceIds.isEmpty() ? Map.of() :
                invoiceRepository.findAllById(invoiceIds).stream()
                        .collect(Collectors.toMap(Invoice::getId, inv -> inv, (left, right) -> left, LinkedHashMap::new));

        Set<UUID> orderIds = payments.stream()
                .map(Payment::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        invoicesById.values().stream()
                .map(Invoice::getOrderId)
                .filter(Objects::nonNull)
                .forEach(orderIds::add);

        Map<UUID, Order> ordersById = orderIds.isEmpty() ? Map.of() :
                orderRepository.findAllById(orderIds).stream()
                        .collect(Collectors.toMap(Order::getId, order -> order, (left, right) -> left, LinkedHashMap::new));

        return new BatchPaymentData(ordersById, invoicesById);
    }

    private CreditOrderDto toOrderDto(Invoice invoice, CreditCustomer customer, Order order) {
        BigDecimal total = creditGuard.money(invoice.getTotalAmount());
        BigDecimal tax = order != null ? creditGuard.money(order.getTotalTaxAmount()) : BigDecimal.ZERO;
        BigDecimal due = creditGuard.money(invoice.getAmountDue());
        BigDecimal paid = total.subtract(due);
        if (paid.compareTo(BigDecimal.ZERO) < 0) paid = BigDecimal.ZERO;
        return CreditOrderDto.builder()
                .orderId(invoice.getOrderId())
                .invoiceId(invoice.getId())
                .orderNo(order != null ? order.getOrderNo() : null)
                .invoiceNo(invoice.getInvoiceNo())
                .customerName(customer != null ? customer.getName() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .amount(total.subtract(tax))
                .tax(tax)
                .total(total)
                .amountPaid(paid)
                .amountDue(due)
                .date(invoice.getInvoiceDate())
                .status(invoice.getStatus())
                .paymentStatus(order != null ? order.getPaymentStatus() : null)
                .build();
    }

    private CreditOrderDto toVendorOrderDto(Invoice invoice, Vendor vendor, Order order) {
        BigDecimal total = creditGuard.money(invoice.getTotalAmount());
        BigDecimal tax = order != null ? creditGuard.money(order.getTotalTaxAmount()) : BigDecimal.ZERO;
        BigDecimal due = creditGuard.money(invoice.getAmountDue());
        BigDecimal paid = total.subtract(due);
        if (paid.compareTo(BigDecimal.ZERO) < 0) paid = BigDecimal.ZERO;
        return CreditOrderDto.builder()
                .orderId(invoice.getOrderId())
                .invoiceId(invoice.getId())
                .orderNo(order != null ? order.getOrderNo() : null)
                .invoiceNo(invoice.getInvoiceNo())
                .customerName(vendor != null ? vendor.getName() : null)
                .customerPhone(vendor != null ? vendor.getPhone() : null)
                .amount(total.subtract(tax))
                .tax(tax)
                .total(total)
                .amountPaid(paid)
                .amountDue(due)
                .date(invoice.getInvoiceDate())
                .status(invoice.getStatus())
                .paymentStatus(order != null ? order.getPaymentStatus() : null)
                .build();
    }

    private CreditReportDto.PaymentTransactionDto toPaymentTransaction(Payment payment, CreditCustomer customer, Map<UUID, Order> ordersById, Map<UUID, Invoice> invoicesById) {
        String orderNo = null;
        Order order = payment.getOrderId() != null ? ordersById.get(payment.getOrderId()) : null;
        if (order == null && payment.getInvoiceId() != null) {
            Invoice inv = invoicesById.get(payment.getInvoiceId());
            if (inv != null && inv.getOrderId() != null) {
                order = ordersById.get(inv.getOrderId());
            }
        }
        if (order != null) {
            orderNo = order.getOrderNo();
        }
        String invoiceNo = null;
        if (payment.getInvoiceId() != null) {
            Invoice inv = invoicesById.get(payment.getInvoiceId());
            if (inv != null) {
                invoiceNo = inv.getInvoiceNo();
            }
        }

        BigDecimal taxAmount = order != null ? order.getTotalTaxAmount() : BigDecimal.ZERO;
        BigDecimal subtotal = order != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal grossAmount = order != null ? order.getGrossAmount() : BigDecimal.ZERO;
        BigDecimal discountAmount = order != null ? order.getTotalDiscountAmount() : BigDecimal.ZERO;
        BigDecimal roundOff = payment.getRoundOffAmount() != null ? payment.getRoundOffAmount()
                : (order != null ? order.getRoundOffAmount() : BigDecimal.ZERO);
        String paymentTypeStr = payment.getPaymentType() != null ? payment.getPaymentType().name() : "INBOUND";

        return CreditReportDto.PaymentTransactionDto.builder()
                .paymentId(payment.getId())
                .creditCustomerId(payment.getCreditCustomerId())
                .customerName(customer != null ? customer.getName() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .transactionDate(payment.getPaymentDate())
                .type(paymentTypeStr)
                .paymentType(paymentTypeStr)
                .paymentTypeLabel("Customer Payment")
                .paymentMethod(payment.getPaymentMethod())
                .amount(creditGuard.money(payment.getAmountPaid()))
                .roundOffAmount(roundOff)
                .taxAmount(taxAmount)
                .subtotal(subtotal)
                .grossAmount(grossAmount)
                .discountAmount(discountAmount)
                .description(payment.getDescription())
                .referenceNo(payment.getReferenceNo())
                .orderId(payment.getOrderId())
                .orderNo(orderNo)
                .invoiceId(payment.getInvoiceId())
                .invoiceNo(invoiceNo)
                .build();
    }

    private CreditReportDto.PaymentTransactionDto toVendorPaymentTransaction(Payment payment, Vendor vendor, Map<UUID, Order> ordersById, Map<UUID, Invoice> invoicesById) {
        String orderNo = null;
        Order order = payment.getOrderId() != null ? ordersById.get(payment.getOrderId()) : null;
        if (order == null && payment.getInvoiceId() != null) {
            Invoice inv = invoicesById.get(payment.getInvoiceId());
            if (inv != null && inv.getOrderId() != null) {
                order = ordersById.get(inv.getOrderId());
            }
        }
        if (order != null) {
            orderNo = order.getOrderNo();
        }
        String invoiceNo = null;
        if (payment.getInvoiceId() != null) {
            Invoice inv = invoicesById.get(payment.getInvoiceId());
            if (inv != null) {
                invoiceNo = inv.getInvoiceNo();
            }
        }

        BigDecimal taxAmount = order != null ? order.getTotalTaxAmount() : BigDecimal.ZERO;
        BigDecimal subtotal = order != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal grossAmount = order != null ? order.getGrossAmount() : BigDecimal.ZERO;
        BigDecimal discountAmount = order != null ? order.getTotalDiscountAmount() : BigDecimal.ZERO;
        BigDecimal roundOff = payment.getRoundOffAmount() != null ? payment.getRoundOffAmount()
                : (order != null ? order.getRoundOffAmount() : BigDecimal.ZERO);
        String paymentTypeStr = payment.getPaymentType() != null ? payment.getPaymentType().name() : "OUTBOUND";

        return CreditReportDto.PaymentTransactionDto.builder()
                .paymentId(payment.getId())
                .creditCustomerId(vendor != null ? vendor.getId() : null)
                .customerName(vendor != null ? vendor.getName() : null)
                .customerPhone(vendor != null ? vendor.getPhone() : null)
                .transactionDate(payment.getPaymentDate())
                .type(paymentTypeStr)
                .paymentType(paymentTypeStr)
                .paymentTypeLabel("Vendor Settlement")
                .paymentMethod(payment.getPaymentMethod())
                .amount(creditGuard.money(payment.getAmountPaid()))
                .roundOffAmount(roundOff)
                .taxAmount(taxAmount)
                .subtotal(subtotal)
                .grossAmount(grossAmount)
                .discountAmount(discountAmount)
                .description(payment.getDescription())
                .referenceNo(payment.getReferenceNo())
                .orderId(payment.getOrderId())
                .orderNo(orderNo)
                .invoiceId(payment.getInvoiceId())
                .invoiceNo(invoiceNo)
                .build();
    }

    private Map<UUID, CreditCustomer> customersById(List<Invoice> invoices, List<Payment> payments) {
        Set<UUID> ids = new LinkedHashSet<>();
        invoices.stream().map(Invoice::getCreditCustomerId).filter(Objects::nonNull).forEach(ids::add);
        payments.stream().map(Payment::getCreditCustomerId).filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) return Map.of();
        return creditCustomerRepository.findByIdInAndClientId(ids, creditGuard.requireClient()).stream()
                .collect(Collectors.toMap(CreditCustomer::getId, customer -> customer, (left, right) -> left, LinkedHashMap::new));
    }
}
