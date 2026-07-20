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
import lombok.RequiredArgsConstructor;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OfflineSequenceLeaseService {

    private static final int DEFAULT_BLOCK_SIZE = 50;
    private static final int MAX_BLOCK_SIZE = 500;

    private final DocumentSequenceRepository sequenceRepository;
    private final OfflineSequenceLeaseRepository leaseRepository;
    private final OrganizationRepository organizationRepository;
    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final SystemConfigurationService configurationService;

    @Transactional
    public List<OfflineSequenceLease> reserveDefaults(UUID requestedTerminalId, Integer requestedBlockSize) {
        validateOfflineSyncEnabled();
        UUID terminalId = resolveTerminalId(requestedTerminalId);
        int blockSize = normalizeBlockSize(requestedBlockSize);
        return List.of(
                reserve(DocumentType.SALE_ORDER, terminalId, blockSize),
                reserve(DocumentType.CUSTOMER_INVOICE, terminalId, blockSize),
                reserve(DocumentType.INBOUND_PAYMENT, terminalId, blockSize)
        );
    }

    @Transactional(readOnly = true)
    public List<OfflineSequenceLease> active(UUID requestedTerminalId) {
        validateOfflineSyncEnabled();
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = getEffectiveOrgId();
        UUID terminalId = resolveTerminalId(requestedTerminalId);
        return leaseRepository.findByClientIdAndOrgIdAndTerminalIdAndStatusOrderByCreatedAtAsc(clientId, orgId, terminalId, "ACTIVE");
    }

    @Transactional
    public void consumeLeasedNumber(DocumentType documentType, String documentNo, UUID requestedTerminalId) {
        if (documentNo == null || documentNo.isBlank()) {
            throw new BusinessException("Offline document number is required");
        }

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = getEffectiveOrgId();
        
        UUID terminalId = requestedTerminalId != null ? requestedTerminalId : TenantContext.getCurrentTerminal();
        if (terminalId == null) {
            terminalId = findTerminalIdFromLeases(clientId, orgId, documentType, documentNo);
        }
        if (terminalId == null) {
            throw new BusinessException("A terminal is required for offline sequence leasing.");
        }

        List<OfflineSequenceLease> leases = leaseRepository
                .findByClientIdAndOrgIdAndTerminalIdAndDocumentTypeAndStatusOrderByStartNumberAsc(
                        clientId, orgId, terminalId, documentType, "ACTIVE");

        for (OfflineSequenceLease lease : leases) {
            for (long number = lease.getStartNumber(); number <= lease.getEndNumber(); number++) {
                if (format(lease.getPrefix(), lease.getSuffix(), lease.getPaddingLength(), number).equals(documentNo)) {
                    if (number < lease.getNextNumber()) {
                        return;
                    }
                    if (lease.getExpiresAt() != null && lease.getExpiresAt().isBefore(LocalDateTime.now())) {
                        lease.setExpiresAt(LocalDateTime.now().plusDays(30));
                    }
                    lease.setNextNumber(number + 1);
                    if (lease.getNextNumber() > lease.getEndNumber()) {
                        lease.setStatus("CONSUMED");
                    }
                    leaseRepository.save(lease);
                    return;
                }
            }
        }

        throw new BusinessException("Offline " + documentType + " number is outside the reserved range for this main terminal.");
    }

    private UUID findTerminalIdFromLeases(UUID clientId, UUID orgId, DocumentType documentType, String documentNo) {
        List<OfflineSequenceLease> leases = leaseRepository.findByClientIdAndOrgIdAndDocumentType(clientId, orgId, documentType);
        for (OfflineSequenceLease lease : leases) {
            for (long number = lease.getStartNumber(); number <= lease.getEndNumber(); number++) {
                if (format(lease.getPrefix(), lease.getSuffix(), lease.getPaddingLength(), number).equals(documentNo)) {
                    return lease.getTerminalId();
                }
            }
        }
        return null;
    }

    private OfflineSequenceLease reserve(DocumentType documentType, UUID terminalId, int blockSize) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = getEffectiveOrgId();

        DocumentSequence sequence = sequenceRepository.findAndLockByDocumentType(clientId, orgId, documentType)
                .orElseGet(() -> createDefaultSequence(clientId, orgId, documentType));

        if (!Boolean.TRUE.equals(sequence.getIsActive())) {
            throw new BusinessException("Document sequence for " + documentType + " is disabled.");
        }

        String prefix = resolvePlaceholders(sequence.getPrefix(), orgId, clientId);
        String suffix = resolvePlaceholders(sequence.getSuffix(), orgId, clientId);
        long start = findAvailableRangeStart(documentType, clientId, orgId, prefix, suffix, sequence.getPaddingLength(), sequence.getNextNumber(), blockSize);
        long end = start + blockSize - 1;
        sequence.setNextNumber(end + 1);
        sequenceRepository.saveAndFlush(sequence);

        String leaseKey = terminalId + ":" + documentType + ":" + start + "-" + end;

        OfflineSequenceLease lease = OfflineSequenceLease.builder()
                .terminalId(terminalId)
                .documentType(documentType)
                .startNumber(start)
                .endNumber(end)
                .nextNumber(start)
                .prefix(prefix)
                .suffix(suffix)
                .paddingLength(sequence.getPaddingLength())
                .status("ACTIVE")
                .leaseKey(leaseKey)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();
        lease.setClientId(clientId);
        lease.setOrgId(orgId);
        return leaseRepository.save(lease);
    }

    private UUID resolveTerminalId(UUID requestedTerminalId) {
        UUID terminalId = requestedTerminalId != null ? requestedTerminalId : TenantContext.getCurrentTerminal();
        if (terminalId == null) {
            throw new BusinessException("A terminal is required for offline sequence leasing.");
        }
        return terminalId;
    }

    private int normalizeBlockSize(Integer requestedBlockSize) {
        int value = requestedBlockSize == null || requestedBlockSize <= 0 ? DEFAULT_BLOCK_SIZE : requestedBlockSize;
        return Math.min(value, MAX_BLOCK_SIZE);
    }

    private UUID getEffectiveOrgId() {
        UUID orgId = TenantContext.getCurrentOrg();
        if (orgId != null) return orgId;

        UUID clientId = TenantContext.getCurrentTenant();
        return organizationRepository.findAllByClientId(clientId).stream()
                .findFirst()
                .map(Organization::getId)
                .orElseThrow(() -> new BusinessException("No branch configuration found."));
    }

    private String resolvePlaceholders(String value, UUID orgId, UUID clientId) {
        if (value == null) return "";
        String year = String.valueOf(LocalDateTime.now().getYear());
        String branchCode = organizationRepository.findByIdAndClientId(orgId, clientId)
                .map(Organization::getBranchCode)
                .orElse("HQ");
        return value.replace("{YYYY}", year).replace("{BRANCH_CODE}", branchCode);
    }

    private String format(String prefix, String suffix, Integer paddingLength, long number) {
        String formatted = String.format("%0" + (paddingLength == null ? 7 : paddingLength) + "d", number);
        return (prefix == null ? "" : prefix) + formatted + (suffix == null ? "" : suffix);
    }

    private long findAvailableRangeStart(
            DocumentType documentType,
            UUID clientId,
            UUID orgId,
            String prefix,
            String suffix,
            Integer paddingLength,
            long candidateStart,
            int blockSize
    ) {
        long start = candidateStart;
        while (rangeContainsUsedNumber(documentType, clientId, orgId, prefix, suffix, paddingLength, start, blockSize)) {
            start++;
        }
        return start;
    }

    private boolean rangeContainsUsedNumber(
            DocumentType documentType,
            UUID clientId,
            UUID orgId,
            String prefix,
            String suffix,
            Integer paddingLength,
            long start,
            int blockSize
    ) {
        long end = start + blockSize - 1;
        if (leaseRepository.existsByClientIdAndOrgIdAndDocumentTypeAndStartNumberLessThanEqualAndEndNumberGreaterThanEqual(
                clientId, orgId, documentType, end, start)) {
            return true;
        }

        for (long number = start; number < start + blockSize; number++) {
            String documentNo = format(prefix, suffix, paddingLength, number);
            if (documentNumberExists(documentType, clientId, orgId, documentNo)) {
                return true;
            }
        }
        return false;
    }

    private boolean documentNumberExists(DocumentType type, UUID clientId, UUID orgId, String documentNo) {
        return switch (type) {
            case SALE_ORDER, QUOTATION, PURCHASE_ORDER, DELIVERY_CHALLAN, EXPENSE ->
                    orderRepository.existsByClientIdAndOrgIdAndOrderNo(clientId, orgId, documentNo);
            case CUSTOMER_INVOICE, VENDOR_BILL, PURCHASE_BILL, EXPENSE_RECEIPT, CREDIT_NOTE, DEBIT_NOTE ->
                    invoiceRepository.existsByClientIdAndOrgIdAndInvoiceNo(clientId, orgId, documentNo);
            case INBOUND_PAYMENT, OUTBOUND_PAYMENT, PAYMENT_IN, PAYMENT_OUT ->
                    paymentRepository.existsByClientIdAndOrgIdAndReferenceNo(clientId, orgId, documentNo);
            case STOCK_ADJUSTMENT, STOCK_TRANSFER, PRODUCTION_ORDER, JOURNAL_ENTRY -> false;
        };
    }

    private DocumentSequence createDefaultSequence(UUID clientId, UUID orgId, DocumentType type) {
        String defaultPrefix = switch (type) {
            case SALE_ORDER -> "SO-";
            case QUOTATION -> "QT-";
            case DELIVERY_CHALLAN -> "DC-";
            case CREDIT_NOTE -> "CN-";
            case PURCHASE_ORDER -> "PO-";
            case PURCHASE_BILL -> "PB-";
            case DEBIT_NOTE -> "DN-";
            case EXPENSE -> "EX-";
            case CUSTOMER_INVOICE -> "INV-";
            case VENDOR_BILL -> "BILL-";
            case EXPENSE_RECEIPT -> "ER-";
            case INBOUND_PAYMENT, PAYMENT_IN -> "REC-";
            case OUTBOUND_PAYMENT, PAYMENT_OUT -> "PAY-";
            case STOCK_ADJUSTMENT -> "SA-";
            case STOCK_TRANSFER -> "ST-";
            case PRODUCTION_ORDER -> "PRD-";
            case JOURNAL_ENTRY -> "JE-";
        };

        DocumentSequence seq = DocumentSequence.builder()
                .documentType(type)
                .prefix(defaultPrefix + "{YYYY}-")
                .suffix("-{BRANCH_CODE}")
                .paddingLength(7)
                .nextNumber(1L)
                .isActive(true)
                .build();

        seq.setClientId(clientId);
        seq.setOrgId(orgId);
        return sequenceRepository.saveAndFlush(seq);
    }

    private void validateOfflineSyncEnabled() {
        ConfigurationDto config = configurationService.getConfiguration();
        if (config == null || !config.isOfflineSyncEnabled()) {
            throw new BusinessException("Offline Sync is disabled for this organization.");
        }
    }
}
