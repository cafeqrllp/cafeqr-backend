package com.restaurant.pos.credit.service;

import com.restaurant.pos.accounting.domain.PaymentAllocation;
import com.restaurant.pos.accounting.repository.PaymentAllocationRepository;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.dto.CreditCustomerDto;
import com.restaurant.pos.credit.dto.CreditCustomerRequest;
import com.restaurant.pos.credit.dto.CreditOrderDto;
import com.restaurant.pos.credit.dto.CreditPaymentRequest;
import com.restaurant.pos.credit.dto.CreditReportDto;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@Service
@RequiredArgsConstructor
public class CreditService {
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE");

    private final CreditCustomerRepository creditCustomerRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final AccountingPostingService accountingPostingService;
    private final SystemConfigurationService configurationService;
    private final BranchContextService branchContext;
    private final DocumentSequenceService sequenceService;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    public boolean isCreditEnabled() {
        try {
            ConfigurationDto config = configurationService.getConfiguration();
            return config != null && config.isCreditEnabled();
        } catch (Exception ex) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<CreditCustomerDto> listCustomers(String status) {
        ensureCreditEnabled();
        UUID clientId = requireClient();
        List<CreditCustomer> customers = status != null && !status.isBlank()
                ? creditCustomerRepository.findByClientIdAndStatusAndIsactiveOrderByNameAsc(clientId, normalizeStatus(status), "Y")
                : creditCustomerRepository.findByClientIdAndIsactiveOrderByNameAsc(clientId, "Y");
        return customers.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public CreditCustomerDto createCustomer(CreditCustomerRequest request) {
        ensureCreditEnabled();
        UUID clientId = requireClient();
        validateCustomerRequest(request);
        String phone = normalizePhone(request.getPhone());
        if (phone != null && creditCustomerRepository.existsByClientIdAndPhoneAndIsactive(clientId, phone, "Y")) {
            throw new BusinessException("A credit customer with this phone already exists");
        }

        Customer linkedCustomer = resolveLinkedCustomer(clientId, request);
        CreditCustomer customer = CreditCustomer.builder()
                .linkedCustomerId(linkedCustomer.getId())
                .name(trimToNull(request.getName()) != null ? request.getName().trim() : linkedCustomer.getName())
                .phone(phone != null ? phone : linkedCustomer.getPhone())
                .email(trimToNull(request.getEmail()) != null ? request.getEmail().trim() : linkedCustomer.getEmail())
                .status("ACTIVE")
                .creditLimit(money(request.getCreditLimit()))
                .openingBalance(money(request.getOpeningBalance()))
                .notes(trimToNull(request.getNotes()))
                .build();
        customer.setClientId(clientId);
        customer.setOrgId(null);
        return toDto(creditCustomerRepository.save(customer));
    }

    @Transactional
    public CreditCustomerDto updateCustomer(UUID id, CreditCustomerRequest request) {
        ensureCreditEnabled();
        UUID clientId = requireClient();
        CreditCustomer customer = getCreditCustomer(id, clientId);
        validateCustomerRequest(request);
        String phone = normalizePhone(request.getPhone());
        if (phone != null && creditCustomerRepository.existsByClientIdAndPhoneAndIsactiveAndIdNot(clientId, phone, "Y", id)) {
            throw new BusinessException("A credit customer with this phone already exists");
        }

        if (request.getLinkedCustomerId() != null && !Objects.equals(request.getLinkedCustomerId(), customer.getLinkedCustomerId())) {
            Customer linked = customerRepository.findByIdAndClientId(request.getLinkedCustomerId(), clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Linked customer not found"));
            customer.setLinkedCustomerId(linked.getId());
        }
        customer.setName(request.getName().trim());
        customer.setPhone(phone);
        customer.setEmail(trimToNull(request.getEmail()));
        customer.setCreditLimit(money(request.getCreditLimit()));
        customer.setOpeningBalance(money(request.getOpeningBalance()));
        customer.setNotes(trimToNull(request.getNotes()));
        syncLinkedCustomer(customer);
        return toDto(creditCustomerRepository.save(customer));
    }

    @Transactional
    public CreditCustomerDto suspendCustomer(UUID id) {
        ensureCreditEnabled();
        CreditCustomer customer = getCreditCustomer(id, requireClient());
        customer.setStatus("SUSPENDED");
        return toDto(creditCustomerRepository.save(customer));
    }

    @Transactional
    public CreditCustomerDto reactivateCustomer(UUID id) {
        ensureCreditEnabled();
        CreditCustomer customer = getCreditCustomer(id, requireClient());
        customer.setStatus("ACTIVE");
        customer.setIsactive("Y");
        return toDto(creditCustomerRepository.save(customer));
    }

    @Transactional(readOnly = true)
    public Page<CreditOrderDto> getCustomerOrders(UUID creditCustomerId, Pageable pageable) {
        ensureCreditEnabled();
        CreditCustomer customer = getCreditCustomer(creditCustomerId, requireClient());
        List<CreditOrderDto> all = openAndClosedInvoices(customer.getId()).stream()
                .map(invoice -> toOrderDto(invoice, customer, resolveOrder(invoice.getOrderId())))
                .sorted(Comparator.comparing(CreditOrderDto::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<CreditOrderDto> pageContent = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(pageContent, pageable, all.size());
    }

    @Transactional(readOnly = true)
    public Page<CreditReportDto.PaymentTransactionDto> getCustomerPayments(UUID creditCustomerId, Pageable pageable) {
        ensureCreditEnabled();
        CreditCustomer customer = getCreditCustomer(creditCustomerId, requireClient());
        List<Payment> payments = paymentRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), customer.getClientId()));
            predicates.add(cb.equal(root.get("creditCustomerId"), customer.getId()));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.not(cb.upper(root.get("docStatus").as(String.class)).in("VOID", "VOIDED"))));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
        List<CreditReportDto.PaymentTransactionDto> all = payments.stream()
                .map(payment -> toPaymentTransaction(payment, customer))
                .sorted(Comparator.comparing(CreditReportDto.PaymentTransactionDto::getTransactionDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<CreditReportDto.PaymentTransactionDto> pageContent = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(pageContent, pageable, all.size());
    }

    @Transactional
    public CreditCustomerDto recordPayment(UUID creditCustomerId, CreditPaymentRequest request) {
        ensureCreditEnabled();
        UUID clientId = requireClient();
        CreditCustomer customer = getCreditCustomer(creditCustomerId, clientId);
        BigDecimal amount = request == null ? BigDecimal.ZERO : money(request.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        UUID orgId = branchContext.requireWriteOrgId(request != null ? request.getOrgId() : null);
        String paymentMethod = normalizePaymentMethod(request != null ? request.getPaymentMethod() : null);

        UUID invoiceId = request != null ? request.getInvoiceId() : null;
        String orderOrInvoiceNo = null;
        if (invoiceId != null) {
            Invoice linkedInvoice = invoiceRepository.findByIdAndClientId(invoiceId, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            validateCreditInvoice(customer, linkedInvoice);
            Order order = resolveOrder(linkedInvoice.getOrderId());
            orderOrInvoiceNo = order != null ? order.getOrderNo() : linkedInvoice.getInvoiceNo();
        }

        String referenceNo = sequenceService.generateNextSequence(DocumentType.INBOUND_PAYMENT, orgId);

        String description;
        if (orderOrInvoiceNo != null) {
            description = "Payment against Order - " + orderOrInvoiceNo;
        } else {
            description = resolvePaymentDescription(customer, request != null ? request.getDescription() : null);
        }

        Payment payment = Payment.builder()
                .paymentType(PaymentType.INBOUND)
                .customerId(customer.getLinkedCustomerId())
                .creditCustomerId(customer.getId())
                .orderId(invoiceId != null ? invoiceRepository.findById(invoiceId).map(Invoice::getOrderId).orElse(null) : null)
                .invoiceId(invoiceId)
                .paymentDate(LocalDateTime.now())
                .paymentMethod(paymentMethod)
                .amountPaid(amount)
                .referenceNo(referenceNo)
                .description(description)
                .build();
        payment.setClientId(clientId);
        payment.setOrgId(orgId);
        Payment savedPayment = paymentRepository.save(payment);

        allocatePayment(customer, savedPayment, request);
        accountingPostingService.postPayment(null, savedPayment);
        return toDto(customer);
    }

    @Transactional(readOnly = true)
    public CreditReportDto report(Instant from, Instant to) {
        ensureCreditEnabled();
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
        LocalDateTime fromDate = from != null ? LocalDateTime.ofInstant(from, zoneId) : null;
        LocalDateTime toDate = to != null ? LocalDateTime.ofInstant(to, zoneId) : null;

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
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), toDate));
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
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), toDate));
            }
            query.orderBy(cb.desc(root.get("paymentDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        Map<UUID, CreditCustomer> customersById = customersById(invoices, payments);
        Map<UUID, Order> ordersById = invoices.stream()
                .map(Invoice::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .map(this::resolveOrder)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Order::getId, order -> order, (left, right) -> left, LinkedHashMap::new));

        BigDecimal creditExtended = invoices.stream().map(Invoice::getTotalAmount).map(this::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paymentsReceived = payments.stream().map(Payment::getAmountPaid).map(this::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outputTax = invoices.stream()
                .map(invoice -> ordersById.get(invoice.getOrderId()))
                .filter(Objects::nonNull)
                .map(Order::getTotalTaxAmount)
                .map(this::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CreditOrderDto> orderRows = invoices.stream()
                .map(invoice -> toOrderDto(invoice, customersById.get(invoice.getCreditCustomerId()), ordersById.get(invoice.getOrderId())))
                .collect(Collectors.toList());
        List<CreditReportDto.PaymentTransactionDto> paymentRows = payments.stream()
                .map(payment -> toPaymentTransaction(payment, customersById.get(payment.getCreditCustomerId())))
                .collect(Collectors.toList());

        long customerCount = customersById.keySet().size();
        return CreditReportDto.builder()
                .creditExtended(creditExtended)
                .paymentsReceived(paymentsReceived)
                .outstanding(creditExtended.subtract(paymentsReceived).setScale(2, RoundingMode.HALF_UP))
                .outputTax(outputTax)
                .orderCount((long) invoices.size())
                .customerCount(customerCount)
                .orders(orderRows)
                .payments(paymentRows)
                .build();
    }

    private void allocatePayment(CreditCustomer customer, Payment payment, CreditPaymentRequest request) {
        UUID orgId = customer.getOrgId() != null ? customer.getOrgId() : TenantContext.getCurrentOrg();
        ConfigurationDto config = orgId != null 
            ? configurationService.getEffectiveConfigurationForBranch(orgId) 
            : configurationService.getConfiguration();
        String configuredMode = config != null ? config.getCreditAllocationMode() : "OLDEST_FIRST";
        String requestedMode = request != null ? request.getAllocationMode() : null;
        String mode = requestedMode != null && !requestedMode.isBlank() ? requestedMode : configuredMode;
        mode = "MANUAL".equalsIgnoreCase(mode) ? "MANUAL" : "OLDEST_FIRST";
        BigDecimal remaining = money(payment.getAmountPaid());
        List<PaymentAllocation> allocations = new ArrayList<>();

        if (request != null && request.getInvoiceId() != null) {
            Invoice invoice = invoiceRepository.findByIdAndClientId(request.getInvoiceId(), customer.getClientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Credit invoice not found"));
            validateCreditInvoice(customer, invoice);
            BigDecimal allocated = money(invoice.getAmountDue()).min(remaining);
            if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(buildAllocation(customer, payment, invoice, allocated, "Direct order-level payment allocation"));
                applyInvoicePayment(invoice, allocated);
                remaining = remaining.subtract(allocated);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            if ("MANUAL".equals(mode) && request != null && request.getAllocations() != null && !request.getAllocations().isEmpty()) {
                for (CreditPaymentRequest.AllocationRequest allocationRequest : request.getAllocations()) {
                    if (allocationRequest == null || allocationRequest.getInvoiceId() == null) {
                        continue;
                    }
                    if (request.getInvoiceId() != null && request.getInvoiceId().equals(allocationRequest.getInvoiceId())) {
                        continue;
                    }
                    Invoice invoice = invoiceRepository.findByIdAndClientId(allocationRequest.getInvoiceId(), customer.getClientId())
                            .orElseThrow(() -> new ResourceNotFoundException("Credit invoice not found"));
                    validateCreditInvoice(customer, invoice);
                    BigDecimal allocated = money(allocationRequest.getAmount()).min(money(invoice.getAmountDue())).min(remaining);
                    if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                        allocations.add(buildAllocation(customer, payment, invoice, allocated, "Manual credit payment allocation"));
                        applyInvoicePayment(invoice, allocated);
                        remaining = remaining.subtract(allocated);
                    }
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                }
            } else {
                for (Invoice invoice : openInvoices(customer.getId())) {
                    if (request != null && request.getInvoiceId() != null && request.getInvoiceId().equals(invoice.getId())) {
                        continue;
                    }
                    BigDecimal allocated = money(invoice.getAmountDue()).min(remaining);
                    if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                        allocations.add(buildAllocation(customer, payment, invoice, allocated, "Oldest-first credit payment allocation"));
                        applyInvoicePayment(invoice, allocated);
                        remaining = remaining.subtract(allocated);
                    }
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                }
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            allocations.add(buildAllocation(customer, payment, null, remaining, "Unallocated credit overpayment"));
        }
        paymentAllocationRepository.saveAll(allocations);
    }

    private PaymentAllocation buildAllocation(CreditCustomer customer, Payment payment, Invoice invoice, BigDecimal amount, String notes) {
        PaymentAllocation allocation = PaymentAllocation.builder()
                .paymentId(payment.getId())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .orderId(invoice != null ? invoice.getOrderId() : null)
                .creditCustomerId(customer.getId())
                .allocatedAmount(money(amount))
                .allocationDate(payment.getPaymentDate())
                .status("POSTED")
                .notes(notes)
                .build();
        allocation.setClientId(payment.getClientId());
        allocation.setOrgId(payment.getOrgId());
        return allocation;
    }

    private void applyInvoicePayment(Invoice invoice, BigDecimal amount) {
        BigDecimal due = money(invoice.getAmountDue()).subtract(money(amount));
        if (due.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setAmountDue(BigDecimal.ZERO);
            invoice.setStatus("PAID");
            invoice.setIsPaid(true);
        } else {
            invoice.setAmountDue(due);
            invoice.setStatus("PARTIAL");
            invoice.setIsPaid(false);
        }
        invoiceRepository.save(invoice);

        if (invoice.getOrderId() != null) {
            orderRepository.findById(invoice.getOrderId()).ifPresent(order -> {
                if (due.compareTo(BigDecimal.ZERO) <= 0) {
                    order.setPaymentStatus("PAID");
                } else if (due.compareTo(invoice.getTotalAmount()) >= 0) {
                    order.setPaymentStatus("PENDING");
                } else {
                    order.setPaymentStatus("PARTIAL");
                }
                orderRepository.save(order);
            });
        }
    }

    private List<Invoice> openInvoices(UUID creditCustomerId) {
        return invoiceRepository.findAll((root, query, cb) -> {
            var predicates = activeCreditInvoicePredicates(root, cb, creditCustomerId);
            predicates.add(cb.greaterThan(root.get("amountDue"), BigDecimal.ZERO));
            query.orderBy(cb.asc(root.get("invoiceDate")), cb.asc(root.get("createdAt")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private List<Invoice> openAndClosedInvoices(UUID creditCustomerId) {
        return invoiceRepository.findAll((root, query, cb) -> {
            var predicates = activeCreditInvoicePredicates(root, cb, creditCustomerId);
            query.orderBy(cb.desc(root.get("invoiceDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private List<jakarta.persistence.criteria.Predicate> activeCreditInvoicePredicates(
            jakarta.persistence.criteria.Root<Invoice> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            UUID creditCustomerId
    ) {
        UUID clientId = requireClient();
        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("clientId"), clientId));
        predicates.add(cb.equal(root.get("creditCustomerId"), creditCustomerId));
        predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
        predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
        predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED")));
        return predicates;
    }

    private void validateCreditInvoice(CreditCustomer customer, Invoice invoice) {
        if (!Objects.equals(invoice.getCreditCustomerId(), customer.getId())) {
            throw new BusinessException("Invoice does not belong to this credit customer");
        }
        if (isVoid(invoice.getStatus()) || isVoid(invoice.getDocStatus())) {
            throw new BusinessException("Voided credit invoices cannot receive payment allocations");
        }
    }

    private CreditCustomerDto toDto(CreditCustomer customer) {
        List<Invoice> invoices = openAndClosedInvoices(customer.getId());
        List<Payment> payments = paymentRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), customer.getClientId()));
            predicates.add(cb.equal(root.get("creditCustomerId"), customer.getId()));
            predicates.add(cb.or(cb.isNull(root.get("isactive")), cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.not(cb.upper(root.get("docStatus").as(String.class)).in("VOID", "VOIDED"))));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
        BigDecimal invoiceTotal = invoices.stream().map(Invoice::getTotalAmount).map(this::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidTotal = payments.stream().map(Payment::getAmountPaid).map(this::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = money(customer.getOpeningBalance()).add(invoiceTotal);
        BigDecimal balance = totalCredit.subtract(paidTotal).setScale(2, RoundingMode.HALF_UP);
        long openCount = invoices.stream().filter(invoice -> money(invoice.getAmountDue()).compareTo(BigDecimal.ZERO) > 0).count();

        return CreditCustomerDto.builder()
                .id(customer.getId())
                .linkedCustomerId(customer.getLinkedCustomerId())
                .name(customer.getName())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .status(customer.getStatus())
                .creditLimit(money(customer.getCreditLimit()))
                .openingBalance(money(customer.getOpeningBalance()))
                .totalCreditExtended(totalCredit)
                .paymentsReceived(paidTotal)
                .balance(balance)
                .openInvoiceCount(openCount)
                .notes(customer.getNotes())
                .build();
    }

    private CreditOrderDto toOrderDto(Invoice invoice, CreditCustomer customer, Order order) {
        BigDecimal total = money(invoice.getTotalAmount());
        BigDecimal tax = order != null ? money(order.getTotalTaxAmount()) : BigDecimal.ZERO;
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
                .amountDue(money(invoice.getAmountDue()))
                .date(invoice.getInvoiceDate())
                .status(invoice.getStatus())
                .paymentStatus(order != null ? order.getPaymentStatus() : null)
                .build();
    }

    private CreditReportDto.PaymentTransactionDto toPaymentTransaction(Payment payment, CreditCustomer customer) {
        String orderNo = null;
        if (payment.getOrderId() != null) {
            orderNo = orderRepository.findById(payment.getOrderId())
                    .map(Order::getOrderNo)
                    .orElse(null);
        }
        String invoiceNo = null;
        if (payment.getInvoiceId() != null) {
            invoiceNo = invoiceRepository.findById(payment.getInvoiceId())
                    .map(Invoice::getInvoiceNo)
                    .orElse(null);
        }

        return CreditReportDto.PaymentTransactionDto.builder()
                .paymentId(payment.getId())
                .creditCustomerId(payment.getCreditCustomerId())
                .customerName(customer != null ? customer.getName() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .transactionDate(payment.getPaymentDate())
                .type("payment")
                .paymentMethod(payment.getPaymentMethod())
                .amount(money(payment.getAmountPaid()))
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
        return ids.stream()
                .map(id -> creditCustomerRepository.findByIdAndClientId(id, requireClient()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(CreditCustomer::getId, customer -> customer, (left, right) -> left, LinkedHashMap::new));
    }

    private CreditCustomer getCreditCustomer(UUID id, UUID clientId) {
        if (id == null) {
            throw new BusinessException("Credit customer is required");
        }
        return creditCustomerRepository.findByIdAndClientId(id, clientId)
                .filter(customer -> !"N".equalsIgnoreCase(customer.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Credit customer not found"));
    }

    private Customer resolveLinkedCustomer(UUID clientId, CreditCustomerRequest request) {
        if (request.getLinkedCustomerId() != null) {
            return customerRepository.findByIdAndClientId(request.getLinkedCustomerId(), clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Linked customer not found"));
        }
        String phone = normalizePhone(request.getPhone());
        if (phone != null) {
            var existing = customerRepository.findFirstByPhoneAndClientIdOrderByCreatedAtAsc(phone, clientId);
            if (existing.isPresent()) {
                Customer customer = existing.get();
                if (trimToNull(customer.getName()) == null && trimToNull(request.getName()) != null) {
                    customer.setName(request.getName().trim());
                }
                customer.setCustomerCategory("CREDIT");
                return customerRepository.save(customer);
            }
        }
        Customer customer = Customer.builder()
                .name(request.getName().trim())
                .phone(phone)
                .email(trimToNull(request.getEmail()))
                .customerCategory("CREDIT")
                .creditLimit(money(request.getCreditLimit()))
                .openingBalance(money(request.getOpeningBalance()))
                .build();
        customer.setClientId(clientId);
        customer.setOrgId(null);
        return customerRepository.save(customer);
    }

    private void syncLinkedCustomer(CreditCustomer creditCustomer) {
        if (creditCustomer.getLinkedCustomerId() == null) {
            return;
        }
        customerRepository.findByIdAndClientId(creditCustomer.getLinkedCustomerId(), creditCustomer.getClientId()).ifPresent(customer -> {
            customer.setName(creditCustomer.getName());
            customer.setPhone(creditCustomer.getPhone());
            customer.setEmail(creditCustomer.getEmail());
            customer.setCustomerCategory("CREDIT");
            customer.setCreditLimit(money(creditCustomer.getCreditLimit()));
            customer.setOpeningBalance(money(creditCustomer.getOpeningBalance()));
            customerRepository.save(customer);
        });
    }

    private void validateCustomerRequest(CreditCustomerRequest request) {
        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("Credit customer name is required");
        }
    }

    private void ensureCreditEnabled() {
        ConfigurationDto config = configurationService.getConfiguration();
        if (config == null || !config.isCreditEnabled()) {
            throw new BusinessException("Credit Ledger is not enabled for this organization");
        }
    }

    private String resolvePaymentDescription(CreditCustomer customer, String description) {
        if (description != null && !description.isBlank()) {
            return "Credit Settlement - " + description.trim();
        }
        return "Credit Settlement - Payment received from " + customer.getName();
    }

    private Order resolveOrder(UUID orderId) {
        if (orderId == null) {
            return null;
        }
        return orderRepository.findById(orderId).orElse(null);
    }

    private UUID requireClient() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        return clientId;
    }

    private String normalizePaymentMethod(String value) {
        if (value == null || value.isBlank()) {
            return "CASH";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
        return "SUSPENDED".equals(normalized) ? "SUSPENDED" : "ACTIVE";
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim().replaceAll("[\\s()\\-]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isVoid(String status) {
        return status != null && ("VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status));
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
