package com.restaurant.pos.credit.command;

import com.restaurant.pos.accounting.repository.PaymentAllocationRepository;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.AuditLogService;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.credit.common.CreditGuard;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchasing.domain.Vendor;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditCommandServiceTest {

    private CreditGuard creditGuard;
    private CreditCustomerRepository creditCustomerRepository;
    private InvoiceRepository invoiceRepository;
    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private PaymentAllocationRepository paymentAllocationRepository;
    private AccountingPostingService accountingPostingService;
    private SystemConfigurationService configurationService;
    private BranchContextService branchContext;
    private DocumentSequenceService sequenceService;
    private AuditLogService auditLogService;

    private CreditCommandService service;

    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        creditGuard = mock(CreditGuard.class);
        creditCustomerRepository = mock(CreditCustomerRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentAllocationRepository = mock(PaymentAllocationRepository.class);
        accountingPostingService = mock(AccountingPostingService.class);
        configurationService = mock(SystemConfigurationService.class);
        branchContext = mock(BranchContextService.class);
        sequenceService = mock(DocumentSequenceService.class);
        auditLogService = mock(AuditLogService.class);

        service = new CreditCommandService(
                creditGuard,
                creditCustomerRepository,
                invoiceRepository,
                orderRepository,
                paymentRepository,
                paymentAllocationRepository,
                accountingPostingService,
                configurationService,
                branchContext,
                sequenceService,
                auditLogService
        );

        when(creditGuard.requireClient()).thenReturn(clientId);
        when(creditGuard.money(any())).thenAnswer(invocation -> {
            BigDecimal val = invocation.getArgument(0);
            return val == null ? BigDecimal.ZERO : val.setScale(2);
        });
    }

    @Test
    void suspendCustomerUpdatesStatusToSuspendedAndLogsAudit() {
        UUID customerId = UUID.randomUUID();
        CreditCustomer customer = CreditCustomer.builder()
                .id(customerId)
                .name("John Doe")
                .status("ACTIVE")
                .isactive("Y")
                .build();
        customer.setClientId(clientId);

        when(creditGuard.getCreditCustomer(customerId, clientId)).thenReturn(customer);
        when(creditCustomerRepository.save(any(CreditCustomer.class))).thenAnswer(i -> i.getArgument(0));

        CreditBPartnerDto result = service.suspendCustomer(customerId);

        assertThat(result.getStatus()).isEqualTo("SUSPENDED");
        verify(creditGuard).ensureCreditEnabled();
        verify(creditCustomerRepository).save(customer);
        verify(auditLogService).logAction("SUSPEND_CREDIT_CUSTOMER", "CreditCustomer", customerId.toString());
    }

    @Test
    void reactivateCustomerUpdatesStatusToActiveAndLogsAudit() {
        UUID customerId = UUID.randomUUID();
        CreditCustomer customer = CreditCustomer.builder()
                .id(customerId)
                .name("John Doe")
                .status("SUSPENDED")
                .isactive("N")
                .build();
        customer.setClientId(clientId);

        when(creditGuard.getCreditCustomer(customerId, clientId)).thenReturn(customer);
        when(creditCustomerRepository.save(any(CreditCustomer.class))).thenAnswer(i -> i.getArgument(0));

        CreditBPartnerDto result = service.reactivateCustomer(customerId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(creditGuard).ensureCreditEnabled();
        verify(creditCustomerRepository).save(customer);
        verify(auditLogService).logAction("REACTIVATE_CREDIT_CUSTOMER", "CreditCustomer", customerId.toString());
    }

    @Test
    void recordPaymentRejectsZeroOrNegativeAmount() {
        UUID customerId = UUID.randomUUID();
        CreditCustomer customer = CreditCustomer.builder().id(customerId).name("John").build();
        when(creditGuard.getCreditCustomerForUpdate(customerId, clientId)).thenReturn(customer);

        RecordPaymentCommand command = RecordPaymentCommand.builder()
                .creditCustomerId(customerId)
                .amount(BigDecimal.ZERO)
                .paymentMethod("CASH")
                .build();

        assertThatThrownBy(() -> service.recordPayment(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payment amount must be greater than zero");
    }

    @Test
    void recordPaymentIsIdempotentWithSameKey() {
        UUID customerId = UUID.randomUUID();
        CreditCustomer customer = CreditCustomer.builder().id(customerId).name("John Doe").status("ACTIVE").build();
        customer.setClientId(clientId);

        when(creditGuard.getCreditCustomerForUpdate(customerId, clientId)).thenReturn(customer);
        String idempotencyKey = "IDEM-KEY-12345";
        Payment existingPayment = Payment.builder().id(UUID.randomUUID()).sourceOperationId(idempotencyKey).build();

        when(paymentRepository.findByClientIdAndSourceOperationId(clientId, idempotencyKey))
                .thenReturn(Optional.of(existingPayment));

        RecordPaymentCommand command = RecordPaymentCommand.builder()
                .creditCustomerId(customerId)
                .amount(new BigDecimal("100.00"))
                .paymentMethod("CASH")
                .idempotencyKey(idempotencyKey)
                .build();

        CreditBPartnerDto result = service.recordPayment(command);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(customerId);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void recordVendorPaymentRejectsUnownedVendorBill() {
        UUID vendorId = UUID.randomUUID();
        UUID otherVendorId = UUID.randomUUID();
        Vendor vendor = Vendor.builder().id(vendorId).name("Supplier Inc").build();
        vendor.setClientId(clientId);

        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = Invoice.builder().id(invoiceId).vendorId(otherVendorId).status("PENDING").build();
        invoice.setClientId(clientId);

        when(creditGuard.isVendor("VENDOR")).thenReturn(true);
        when(creditGuard.getVendorForUpdate(vendorId, clientId)).thenReturn(vendor);
        when(invoiceRepository.findByIdAndClientId(invoiceId, clientId)).thenReturn(Optional.of(invoice));

        RecordPaymentCommand command = RecordPaymentCommand.builder()
                .creditCustomerId(vendorId)
                .partnerType("VENDOR")
                .invoiceId(invoiceId)
                .amount(new BigDecimal("500.00"))
                .paymentMethod("BANK_TRANSFER")
                .build();

        assertThatThrownBy(() -> service.recordPayment(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Vendor bill does not belong to this vendor");
    }
}
