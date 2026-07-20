package com.restaurant.pos.sequence.service;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.sequence.domain.DocumentSequence;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.domain.OfflineSequenceLease;
import com.restaurant.pos.sequence.repository.DocumentSequenceRepository;
import com.restaurant.pos.sequence.repository.OfflineSequenceLeaseRepository;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OfflineSequenceLeaseServiceTest {

    private DocumentSequenceRepository sequenceRepository;
    private OfflineSequenceLeaseRepository leaseRepository;
    private OrganizationRepository organizationRepository;
    private OrderRepository orderRepository;
    private InvoiceRepository invoiceRepository;
    private PaymentRepository paymentRepository;
    private SystemConfigurationService configurationService;

    private OfflineSequenceLeaseService leaseService;
    private UUID clientId;
    private UUID orgId;
    private UUID terminalId;

    @BeforeEach
    void setUp() {
        sequenceRepository = mock(DocumentSequenceRepository.class);
        leaseRepository = mock(OfflineSequenceLeaseRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        orderRepository = mock(OrderRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        configurationService = mock(SystemConfigurationService.class);

        leaseService = new OfflineSequenceLeaseService(
                sequenceRepository,
                leaseRepository,
                organizationRepository,
                orderRepository,
                invoiceRepository,
                paymentRepository,
                configurationService
        );

        when(configurationService.getConfiguration()).thenReturn(ConfigurationDto.builder()
                .offlineSyncEnabled(true)
                .build());

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        terminalId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);
        TenantContext.setCurrentTerminal(terminalId);
    }

    @Test
    void testReserveDefaults() {
        DocumentSequence seq = DocumentSequence.builder()
                .documentType(DocumentType.SALE_ORDER)
                .prefix("SO-{YYYY}-")
                .suffix("-{BRANCH_CODE}")
                .paddingLength(7)
                .nextNumber(1L)
                .isActive(true)
                .build();

        Organization org = Organization.builder()
                .id(orgId)
                .branchCode("HQ")
                .build();

        when(sequenceRepository.findAndLockByDocumentType(any(), any(), any()))
                .thenReturn(Optional.of(seq));
        when(organizationRepository.findByIdAndClientId(any(), any()))
                .thenReturn(Optional.of(org));
        when(leaseRepository.save(any(OfflineSequenceLease.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<OfflineSequenceLease> leases = leaseService.reserveDefaults(terminalId, 50);

        assertThat(leases).hasSize(3);
        assertThat(leases.get(0).getDocumentType()).isEqualTo(DocumentType.SALE_ORDER);
        assertThat(leases.get(0).getStartNumber()).isEqualTo(1L);
        assertThat(leases.get(0).getEndNumber()).isEqualTo(50L);
        assertThat(leases.get(0).getSuffix()).contains("HQ");
    }

    @Test
    void testConsumeLeasedNumberSuccess() {
        OfflineSequenceLease lease = OfflineSequenceLease.builder()
                .terminalId(terminalId)
                .documentType(DocumentType.SALE_ORDER)
                .startNumber(100L)
                .endNumber(150L)
                .nextNumber(100L)
                .prefix("SO-2026-")
                .suffix("-HQ")
                .paddingLength(7)
                .status("ACTIVE")
                .build();

        when(leaseRepository.findByClientIdAndOrgIdAndTerminalIdAndDocumentTypeAndStatusOrderByStartNumberAsc(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(lease));

        // Consume "SO-2026-0000100-HQ"
        leaseService.consumeLeasedNumber(DocumentType.SALE_ORDER, "SO-2026-0000100-HQ", terminalId);

        assertThat(lease.getNextNumber()).isEqualTo(101L);
        verify(leaseRepository).save(lease);
    }

    @Test
    void testConsumeLeasedNumberOutsideRangeThrows() {
        OfflineSequenceLease lease = OfflineSequenceLease.builder()
                .terminalId(terminalId)
                .documentType(DocumentType.SALE_ORDER)
                .startNumber(100L)
                .endNumber(150L)
                .nextNumber(100L)
                .prefix("SO-2026-")
                .suffix("-HQ")
                .paddingLength(7)
                .status("ACTIVE")
                .build();

        when(leaseRepository.findByClientIdAndOrgIdAndTerminalIdAndDocumentTypeAndStatusOrderByStartNumberAsc(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(lease));

        // Consume "SO-2026-0000200-HQ" (not in lease range [100, 150])
        assertThatThrownBy(() -> leaseService.consumeLeasedNumber(DocumentType.SALE_ORDER, "SO-2026-0000200-HQ", terminalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outside the reserved range");
    }

    @Test
    void testConsumeLeasedNumberAutoExtendsExpiredLease() {
        java.time.LocalDateTime pastTime = java.time.LocalDateTime.now().minusDays(5);
        OfflineSequenceLease lease = OfflineSequenceLease.builder()
                .terminalId(terminalId)
                .documentType(DocumentType.SALE_ORDER)
                .startNumber(100L)
                .endNumber(150L)
                .nextNumber(100L)
                .prefix("SO-2026-")
                .suffix("-HQ")
                .paddingLength(7)
                .status("ACTIVE")
                .expiresAt(pastTime)
                .build();

        when(leaseRepository.findByClientIdAndOrgIdAndTerminalIdAndDocumentTypeAndStatusOrderByStartNumberAsc(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(lease));

        // Consume "SO-2026-0000100-HQ" on expired lease
        leaseService.consumeLeasedNumber(DocumentType.SALE_ORDER, "SO-2026-0000100-HQ", terminalId);

        assertThat(lease.getNextNumber()).isEqualTo(101L);
        assertThat(lease.getExpiresAt()).isAfter(java.time.LocalDateTime.now().plusDays(29));
        verify(leaseRepository).save(lease);
    }
}
