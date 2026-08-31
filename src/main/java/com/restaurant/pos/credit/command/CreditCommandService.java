package com.restaurant.pos.credit.command;

import com.restaurant.pos.accounting.domain.PaymentAllocation;
import com.restaurant.pos.accounting.repository.PaymentAllocationRepository;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.common.CreditGuard;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchasing.domain.Vendor;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;

import com.restaurant.pos.credit.dto.CreateCreditCustomerRequest;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CQRS Command Service for the Credit module.
 * Handles all state-mutating operations: suspend, reactivate, record payment
 * for both Customers (INBOUND) and Vendors (OUTBOUND).
 */
@Service
@RequiredArgsConstructor
public class CreditCommandService {

    private final CreditGuard creditGuard;
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
    private final com.restaurant.pos.common.service.AuditLogService auditLogService;

    // ── Suspend / Reactivate ─────────────────────────────────────────────────

    @Transactional
    public CreditBPartnerDto suspendCustomer(UUID id) {
        creditGuard.ensureCreditEnabled();
        CreditCustomer customer = creditGuard.getCreditCustomer(id, creditGuard.requireClient());
        customer.setStatus(com.restaurant.pos.credit.domain.CreditCustomerStatus.SUSPENDED.name());
        CreditCustomer saved = creditCustomerRepository.save(customer);
        auditLogService.logAction("SUSPEND_CREDIT_CUSTOMER", "CreditCustomer", id.toString());
        return toDtoWithBalance(saved);
    }

    @Transactional
    public CreditBPartnerDto reactivateCustomer(UUID id) {
        creditGuard.ensureCreditEnabled();
        CreditCustomer customer = creditGuard.getCreditCustomer(id, creditGuard.requireClient());
        customer.setStatus(com.restaurant.pos.credit.domain.CreditCustomerStatus.ACTIVE.name());
        customer.setIsactive("Y");
        CreditCustomer saved = creditCustomerRepository.save(customer);
        auditLogService.logAction("REACTIVATE_CREDIT_CUSTOMER", "CreditCustomer", id.toString());
        return toDtoWithBalance(saved);
    }

    @Transactional
    public CreditBPartnerDto createCreditCustomer(CreateCreditCustomerRequest request) {
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        if (request.getName() == null || request.getName().trim().isBlank()) {
            throw new BusinessException("Customer name is required");
        }

        String phone = request.getPhone() != null ? request.getPhone().trim().replaceAll("[\\s()\\-]", "") : null;

        // Find or create linked Customer record
        Customer customer = null;
        if (phone != null && !phone.isBlank()) {
            customer = customerRepository.findByPhoneAndClientId(phone, clientId).orElse(null);
        }

        if (customer == null) {
            customer = Customer.builder()
                    .name(request.getName().trim())
                    .phone(phone)
                    .email(request.getEmail())
                    .creditLimit(request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO)
                    .openingBalance(request.getOpeningBalance() != null ? request.getOpeningBalance() : BigDecimal.ZERO)
                    .build();
            customer.setClientId(clientId);
            customer.setOrgId(null);
            customer = customerRepository.save(customer);
        }

        // Find or create CreditCustomer record
        var existingCredit = creditCustomerRepository.findByClientIdAndLinkedCustomerIdAndIsactive(clientId,
                customer.getId(), "Y");
        if (existingCredit.isPresent()) {
            return toDtoWithBalance(existingCredit.get());
        }

        CreditCustomer creditCustomer = CreditCustomer.builder()
                .linkedCustomerId(customer.getId())
                .name(customer.getName())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .status("ACTIVE")
                .isactive("Y")
                .creditLimit(request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO)
                .openingBalance(request.getOpeningBalance() != null ? request.getOpeningBalance() : BigDecimal.ZERO)
                .notes(request.getNotes())
                .build();
        creditCustomer.setClientId(clientId);

        CreditCustomer saved = creditCustomerRepository.save(creditCustomer);
        auditLogService.logAction("CREATE_CREDIT_CUSTOMER", "CreditCustomer", saved.getId().toString());
        return toDtoWithBalance(saved);
    }

    @Transactional
    public CreditBPartnerDto updateCreditCustomer(UUID id, CreateCreditCustomerRequest request) {
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        CreditCustomer customer = creditGuard.getCreditCustomerForUpdate(id, clientId);
        if (request.getName() != null && !request.getName().trim().isBlank()) {
            customer.setName(request.getName().trim());
        }
        if (request.getPhone() != null) {
            customer.setPhone(request.getPhone().trim().replaceAll("[\\s()\\-]", ""));
        }
        if (request.getEmail() != null) {
            customer.setEmail(request.getEmail());
        }
        if (request.getCreditLimit() != null) {
            customer.setCreditLimit(request.getCreditLimit());
        }
        if (request.getOpeningBalance() != null) {
            customer.setOpeningBalance(request.getOpeningBalance());
        }
        if (request.getNotes() != null) {
            customer.setNotes(request.getNotes());
        }

        if (customer.getLinkedCustomerId() != null) {
            customerRepository.findByIdAndClientId(customer.getLinkedCustomerId(), clientId).ifPresent(c -> {
                c.setName(customer.getName());
                c.setPhone(customer.getPhone());
                c.setEmail(customer.getEmail());
                c.setCreditLimit(customer.getCreditLimit());
                c.setOpeningBalance(customer.getOpeningBalance());
                customerRepository.save(c);
            });
        }

        CreditCustomer saved = creditCustomerRepository.save(customer);
        auditLogService.logAction("UPDATE_CREDIT_CUSTOMER", "CreditCustomer", id.toString());
        return toDtoWithBalance(saved);
    }

    @Transactional
    public void deleteCreditCustomer(UUID id) {
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();
        CreditCustomer customer = creditGuard.getCreditCustomerForUpdate(id, clientId);
        customer.setIsactive("N");
        customer.setStatus("SUSPENDED");
        creditCustomerRepository.save(customer);
        auditLogService.logAction("DELETE_CREDIT_CUSTOMER", "CreditCustomer", id.toString());
    }

    // ── Record Payment ───────────────────────────────────────────────────────

    @Transactional
    public CreditBPartnerDto recordPayment(RecordPaymentCommand command) {
        creditGuard.ensureCreditEnabled();
        UUID clientId = creditGuard.requireClient();

        if (creditGuard.isVendor(command.getPartnerType())) {
            return recordVendorPayment(command, clientId);
        }

        CreditCustomer customer = creditGuard.getCreditCustomerForUpdate(command.getCreditCustomerId(), clientId);
        String idempotencyKey = command.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingPayment = paymentRepository.findByClientIdAndSourceOperationId(clientId, idempotencyKey);
            if (existingPayment.isPresent()) {
                return toDtoWithBalance(customer);
            }
        }

        BigDecimal amount = creditGuard.money(command.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        UUID orgId = branchContext.requireWriteOrgId(command.getOrgId());
        String paymentMethod = creditGuard.normalizePaymentMethod(command.getPaymentMethod());

        UUID invoiceId = command.getInvoiceId();
        String orderOrInvoiceNo = null;
        Order linkedCreditOrder = null;
        if (invoiceId != null) {
            Invoice linkedInvoice = invoiceRepository.findByIdAndClientId(invoiceId, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            validateCreditInvoice(customer, linkedInvoice);
            linkedCreditOrder = creditGuard.resolveOrder(linkedInvoice.getOrderId());
            orderOrInvoiceNo = linkedCreditOrder != null ? linkedCreditOrder.getOrderNo()
                    : linkedInvoice.getInvoiceNo();
        }

        String referenceNo = sequenceService.generateNextSequence(DocumentType.INBOUND_PAYMENT, orgId);

        String description;
        if (orderOrInvoiceNo != null) {
            description = "Payment against Order - " + orderOrInvoiceNo;
        } else {
            description = resolvePaymentDescription(customer, command.getDescription());
        }

        BigDecimal roundOffAmount = linkedCreditOrder != null ? linkedCreditOrder.getRoundOffAmount() : null;

        Payment payment = Payment.builder()
                .paymentType(PaymentType.INBOUND)
                .customerId(customer.getLinkedCustomerId())
                .creditCustomerId(customer.getId())
                .orderId(linkedCreditOrder != null ? linkedCreditOrder.getId() : null)
                .invoiceId(invoiceId)
                .paymentDate(LocalDateTime.now(ZoneOffset.UTC))
                .paymentMethod(paymentMethod)
                .amountPaid(amount)
                .roundOffAmount(roundOffAmount)
                .referenceNo(referenceNo)
                .sourceOperationId(idempotencyKey)
                .description(description)
                .build();
        payment.setClientId(clientId);
        payment.setOrgId(orgId);
        Payment savedPayment = paymentRepository.save(payment);

        allocatePayment(customer, savedPayment, command);
        accountingPostingService.postPayment(null, savedPayment);
        return toDtoWithBalance(customer);
    }

    private CreditBPartnerDto recordVendorPayment(RecordPaymentCommand command, UUID clientId) {
        Vendor vendor = creditGuard.getVendorForUpdate(command.getCreditCustomerId(), clientId);
        String idempotencyKey = command.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingPayment = paymentRepository.findByClientIdAndSourceOperationId(clientId, idempotencyKey);
            if (existingPayment.isPresent()) {
                return toVendorDtoWithBalance(vendor);
            }
        }

        BigDecimal amount = creditGuard.money(command.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        UUID orgId = branchContext.requireWriteOrgId(command.getOrgId());
        String paymentMethod = creditGuard.normalizePaymentMethod(command.getPaymentMethod());

        UUID invoiceId = command.getInvoiceId();
        String orderOrInvoiceNo = null;
        Order linkedOrder = null;
        if (invoiceId != null) {
            Invoice linkedInvoice = invoiceRepository.findByIdAndClientId(invoiceId, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor bill not found"));
            validateVendorInvoice(vendor, linkedInvoice);
            linkedOrder = creditGuard.resolveOrder(linkedInvoice.getOrderId());
            orderOrInvoiceNo = linkedOrder != null ? linkedOrder.getOrderNo() : linkedInvoice.getInvoiceNo();
        }

        String referenceNo = sequenceService.generateNextSequence(DocumentType.OUTBOUND_PAYMENT, orgId);
        String description = orderOrInvoiceNo != null
                ? "Vendor Payment against Order - " + orderOrInvoiceNo
                : (command.getDescription() != null && !command.getDescription().isBlank()
                        ? "Vendor Settlement - " + command.getDescription().trim()
                        : "Vendor Settlement - Payment paid to " + vendor.getName());

        Payment payment = Payment.builder()
                .paymentType(PaymentType.OUTBOUND)
                .creditCustomerId(vendor.getId())
                .orderId(linkedOrder != null ? linkedOrder.getId() : null)
                .invoiceId(invoiceId)
                .paymentDate(LocalDateTime.now(ZoneOffset.UTC))
                .paymentMethod(paymentMethod)
                .amountPaid(amount)
                .referenceNo(referenceNo)
                .sourceOperationId(idempotencyKey)
                .description(description)
                .docStatus("COMPLETED")
                .isactive("Y")
                .build();
        payment.setClientId(clientId);
        payment.setOrgId(orgId);
        Payment savedPayment = paymentRepository.save(payment);

        allocateVendorPayment(vendor, savedPayment, command);
        accountingPostingService.postPayment(null, savedPayment);
        return toVendorDtoWithBalance(vendor);
    }

    // ── Customer Payment allocation ──────────────────────────────────────────

    private void allocatePayment(CreditCustomer customer, Payment payment, RecordPaymentCommand command) {
        UUID orgId = customer.getOrgId() != null ? customer.getOrgId() : TenantContext.getCurrentOrg();
        ConfigurationDto config = orgId != null
                ? configurationService.getEffectiveConfigurationForBranch(orgId)
                : configurationService.getConfiguration();
        String configuredMode = config != null ? config.getCreditAllocationMode() : "OLDEST_FIRST";
        String requestedMode = command.getAllocationMode();
        String mode = requestedMode != null && !requestedMode.isBlank() ? requestedMode : configuredMode;
        mode = "MANUAL".equalsIgnoreCase(mode) ? "MANUAL" : "OLDEST_FIRST";
        BigDecimal remaining = creditGuard.money(payment.getAmountPaid());
        List<PaymentAllocation> allocations = new ArrayList<>();

        if (command.getInvoiceId() != null) {
            Invoice invoice = invoiceRepository.findByIdAndClientId(command.getInvoiceId(), customer.getClientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Credit invoice not found"));
            validateCreditInvoice(customer, invoice);
            BigDecimal allocated = creditGuard.money(invoice.getAmountDue()).min(remaining);
            if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(buildAllocation(customer.getId(), payment, invoice, allocated,
                        "Direct order-level payment allocation"));
                applyInvoicePayment(invoice, allocated);
                remaining = remaining.subtract(allocated);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            if ("MANUAL".equals(mode) && command.getAllocations() != null && !command.getAllocations().isEmpty()) {
                for (RecordPaymentCommand.AllocationEntry allocationEntry : command.getAllocations()) {
                    if (allocationEntry == null || allocationEntry.getInvoiceId() == null) {
                        continue;
                    }
                    if (command.getInvoiceId() != null
                            && command.getInvoiceId().equals(allocationEntry.getInvoiceId())) {
                        continue;
                    }
                    Invoice invoice = invoiceRepository
                            .findByIdAndClientId(allocationEntry.getInvoiceId(), customer.getClientId())
                            .orElseThrow(() -> new ResourceNotFoundException("Credit invoice not found"));
                    validateCreditInvoice(customer, invoice);
                    BigDecimal allocated = creditGuard.money(allocationEntry.getAmount())
                            .min(creditGuard.money(invoice.getAmountDue())).min(remaining);
                    if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                        allocations.add(buildAllocation(customer.getId(), payment, invoice, allocated,
                                "Manual credit payment allocation"));
                        applyInvoicePayment(invoice, allocated);
                        remaining = remaining.subtract(allocated);
                    }
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                }
            } else {
                for (Invoice invoice : openInvoices(customer.getId())) {
                    if (command.getInvoiceId() != null && command.getInvoiceId().equals(invoice.getId())) {
                        continue;
                    }
                    BigDecimal allocated = creditGuard.money(invoice.getAmountDue()).min(remaining);
                    if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                        allocations.add(buildAllocation(customer.getId(), payment, invoice, allocated,
                                "Oldest-first credit payment allocation"));
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
            allocations
                    .add(buildAllocation(customer.getId(), payment, null, remaining, "Unallocated credit overpayment"));
        }
        paymentAllocationRepository.saveAll(allocations);
    }

    // ── Vendor Payment allocation ────────────────────────────────────────────

    private void allocateVendorPayment(Vendor vendor, Payment payment, RecordPaymentCommand command) {
        BigDecimal remaining = creditGuard.money(payment.getAmountPaid());
        List<PaymentAllocation> allocations = new ArrayList<>();

        if (command.getInvoiceId() != null) {
            Invoice invoice = invoiceRepository.findByIdAndClientId(command.getInvoiceId(), vendor.getClientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor bill not found"));
            validateVendorInvoice(vendor, invoice);
            BigDecimal allocated = creditGuard.money(invoice.getAmountDue()).min(remaining);
            if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(buildAllocation(vendor.getId(), payment, invoice, allocated,
                        "Direct vendor bill payment allocation"));
                applyInvoicePayment(invoice, allocated);
                remaining = remaining.subtract(allocated);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            List<Invoice> openVendorBills = openVendorBills(vendor.getId());
            for (Invoice invoice : openVendorBills) {
                if (command.getInvoiceId() != null && command.getInvoiceId().equals(invoice.getId())) {
                    continue;
                }
                BigDecimal allocated = creditGuard.money(invoice.getAmountDue()).min(remaining);
                if (allocated.compareTo(BigDecimal.ZERO) > 0) {
                    allocations.add(buildAllocation(vendor.getId(), payment, invoice, allocated,
                            "Vendor bill settlement allocation"));
                    applyInvoicePayment(invoice, allocated);
                    remaining = remaining.subtract(allocated);
                }
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            allocations
                    .add(buildAllocation(vendor.getId(), payment, null, remaining, "Unallocated vendor overpayment"));
        }
        paymentAllocationRepository.saveAll(allocations);
    }

    private PaymentAllocation buildAllocation(UUID partnerId, Payment payment, Invoice invoice, BigDecimal amount,
            String notes) {
        PaymentAllocation allocation = PaymentAllocation.builder()
                .paymentId(payment.getId())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .orderId(invoice != null ? invoice.getOrderId() : null)
                .creditCustomerId(partnerId)
                .allocatedAmount(creditGuard.money(amount))
                .allocationDate(payment.getPaymentDate())
                .status("POSTED")
                .notes(notes)
                .build();
        allocation.setClientId(payment.getClientId());
        allocation.setOrgId(payment.getOrgId());
        return allocation;
    }

    private void applyInvoicePayment(Invoice invoice, BigDecimal amount) {
        BigDecimal due = creditGuard.money(invoice.getAmountDue()).subtract(creditGuard.money(amount));
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Invoice> openInvoices(UUID creditCustomerId) {
        return invoiceRepository.findAll((root, query, cb) -> {
            var predicates = activeCreditInvoicePredicates(root, cb, creditCustomerId);
            predicates.add(cb.greaterThan(root.get("amountDue"), BigDecimal.ZERO));
            query.orderBy(cb.asc(root.get("invoiceDate")), cb.asc(root.get("createdAt")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private List<Invoice> openVendorBills(UUID vendorId) {
        return invoiceRepository.findAll((root, query, cb) -> {
            UUID clientId = creditGuard.requireClient();
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            predicates.add(cb.or(
                    cb.equal(root.get("vendorId"), vendorId),
                    cb.equal(root.get("creditCustomerId"), vendorId)));
            predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.VENDOR_BILL));
            predicates.add(cb.greaterThan(root.get("amountDue"), BigDecimal.ZERO));
            predicates.add(cb.or(cb.isNull(root.get("isactive")),
                    cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
            predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED")));
            query.orderBy(cb.asc(root.get("invoiceDate")), cb.asc(root.get("createdAt")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private List<jakarta.persistence.criteria.Predicate> activeCreditInvoicePredicates(
            jakarta.persistence.criteria.Root<Invoice> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            UUID creditCustomerId) {
        UUID clientId = creditGuard.requireClient();
        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("clientId"), clientId));
        predicates.add(cb.equal(root.get("creditCustomerId"), creditCustomerId));
        predicates.add(cb.equal(root.get("invoiceType"), InvoiceType.CUSTOMER_INVOICE));
        predicates.add(cb.or(cb.isNull(root.get("isactive")),
                cb.notEqual(cb.upper(root.get("isactive").as(String.class)), "N")));
        predicates.add(cb.not(cb.upper(root.get("status").as(String.class)).in("VOID", "VOIDED")));
        return predicates;
    }

    private String resolvePaymentDescription(CreditCustomer customer, String description) {
        if (description != null && !description.isBlank()) {
            return "Credit Settlement - " + description.trim();
        }
        return "Credit Settlement - Payment received from " + customer.getName();
    }

    private void validateCreditInvoice(CreditCustomer customer, Invoice invoice) {
        if (!Objects.equals(invoice.getCreditCustomerId(), customer.getId())) {
            throw new BusinessException("Invoice does not belong to this credit customer");
        }
        if (creditGuard.isVoid(invoice.getStatus()) || creditGuard.isVoid(invoice.getDocStatus())) {
            throw new BusinessException("Voided credit invoices cannot receive payment allocations");
        }
    }

    private void validateVendorInvoice(Vendor vendor, Invoice invoice) {
        if (invoice == null) {
            throw new ResourceNotFoundException("Vendor bill not found");
        }
        if (!Objects.equals(invoice.getVendorId(), vendor.getId())
                && !Objects.equals(invoice.getCreditCustomerId(), vendor.getId())) {
            throw new BusinessException("Vendor bill does not belong to this vendor");
        }
        if (creditGuard.isVoid(invoice.getStatus()) || creditGuard.isVoid(invoice.getDocStatus())) {
            throw new BusinessException("Voided vendor bills cannot receive payment allocations");
        }
    }

    private CreditBPartnerDto toDtoWithBalance(CreditCustomer customer) {
        BigDecimal invoiceTotal = creditGuard
                .money(invoiceRepository.sumTotalAmountByCustomer(customer.getClientId(), customer.getId()));
        BigDecimal pendingInvoiceBalance = creditGuard
                .money(invoiceRepository.sumAmountDueByCustomer(customer.getClientId(), customer.getId()));
        BigDecimal paidTotal = creditGuard
                .money(paymentRepository.sumPaidByCustomer(customer.getClientId(), customer.getId()));
        long openCount = invoiceRepository.countOpenInvoicesByCustomer(customer.getClientId(), customer.getId());

        BigDecimal totalCredit = creditGuard.money(customer.getOpeningBalance()).add(invoiceTotal);
        BigDecimal balance = creditGuard.money(customer.getOpeningBalance())
                .add(pendingInvoiceBalance)
                .setScale(2, RoundingMode.HALF_UP);

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
                .paymentsReceived(paidTotal)
                .balance(balance)
                .openInvoiceCount(openCount)
                .notes(customer.getNotes())
                .partnerType("CUSTOMER")
                .build();
    }

    private CreditBPartnerDto toVendorDtoWithBalance(Vendor vendor) {
        BigDecimal invoiceTotal = creditGuard
                .money(invoiceRepository.sumTotalAmountByVendor(vendor.getClientId(), vendor.getId()));
        BigDecimal pendingInvoiceBalance = creditGuard
                .money(invoiceRepository.sumAmountDueByVendor(vendor.getClientId(), vendor.getId()));
        BigDecimal paidTotal = creditGuard
                .money(paymentRepository.sumPaidByVendor(vendor.getClientId(), vendor.getId()));
        long openCount = invoiceRepository.countOpenInvoicesByVendor(vendor.getClientId(), vendor.getId());

        BigDecimal totalCredit = creditGuard.money(vendor.getOpeningBalance()).add(invoiceTotal);
        BigDecimal balance = creditGuard.money(vendor.getOpeningBalance())
                .add(pendingInvoiceBalance)
                .setScale(2, RoundingMode.HALF_UP);

        return CreditBPartnerDto.builder()
                .id(vendor.getId())
                .name(vendor.getName())
                .phone(vendor.getPhone())
                .email(vendor.getEmail())
                .status("ACTIVE")
                .creditLimit(creditGuard.money(vendor.getCreditLimit()))
                .openingBalance(creditGuard.money(vendor.getOpeningBalance()))
                .totalCreditExtended(totalCredit)
                .paymentsReceived(paidTotal)
                .balance(balance)
                .openInvoiceCount(openCount)
                .notes(vendor.getAddress())
                .partnerType("VENDOR")
                .build();
    }
}
