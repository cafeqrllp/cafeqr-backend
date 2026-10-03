package com.restaurant.pos.sequence.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.sequence.domain.DocumentSequence;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.repository.DocumentSequenceRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSequenceService {

    private final DocumentSequenceRepository sequenceRepository;
    private final OrganizationRepository organizationRepository;
    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final BranchContextService branchContext;

    /**
     * CRITICAL: Uses REQUIRES_NEW to ensure the sequence increment is immediately committed
     * regardless of whether the parent transaction succeeds or rolls back. This prevents
     * issued values are committed even if the parent transaction rolls back, which
     * guarantees no duplicate numbers are ever issued.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateNextSequence(DocumentType type) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = getEffectiveOrgId();
        return generateNextSequence(clientId, orgId, type);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateNextSequence(DocumentType type, UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID resolvedOrgId = branchContext.requireWriteOrgId(orgId);
        return generateNextSequence(clientId, resolvedOrgId, type);
    }

    /**
     * Generates a sequence number using explicit client and org IDs.
     * Use this for PUBLIC / unauthenticated endpoints (e.g. QR Menu ordering)
     * where TenantContext is not populated and BranchContextService would throw.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateNextSequenceExplicit(UUID clientId, UUID orgId, DocumentType type) {
        return generateNextSequence(clientId, orgId, type);
    }

    private String generateNextSequence(UUID clientId, UUID orgId, DocumentType type) {

        // 1. Pessimistic lock fetches the row, blocking other threads
        DocumentSequence sequence = sequenceRepository.findAndLockByDocumentType(clientId, orgId, type)
                .orElseGet(() -> createDefaultSequence(clientId, orgId, type));

        if (!sequence.getIsActive()) {
            throw new BusinessException("Document sequence for " + type + " is disabled.");
        }

        String prefix = resolvePlaceholders(sequence.getPrefix(), orgId, clientId);
        String suffix = resolvePlaceholders(sequence.getSuffix(), orgId, clientId);
        long currentNum = sequence.getNextNumber();
        String fullSequence = format(prefix, suffix, sequence.getPaddingLength(), currentNum);

        while (documentNumberExists(type, clientId, orgId, fullSequence)) {
            currentNum++;
            fullSequence = format(prefix, suffix, sequence.getPaddingLength(), currentNum);
        }

        sequence.setNextNumber(currentNum + 1);
        sequenceRepository.saveAndFlush(sequence);

        return fullSequence;
    }

    @Transactional
    public List<DocumentSequence> getAllSequences() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        
        if (orgId == null) {
            return sequenceRepository.findByClientId(clientId);
        }
        
        List<DocumentSequence> existing = sequenceRepository.findByClientIdAndOrgId(clientId, orgId);
        
        // Auto-seed missing document sequence types for the organization
        Set<DocumentType> existingTypes = existing.stream()
                .map(DocumentSequence::getDocumentType)
                .collect(Collectors.toSet());

        boolean seededNew = false;
        for (DocumentType type : DocumentType.values()) {
            if (!existingTypes.contains(type)) {
                createDefaultSequence(clientId, orgId, type);
                seededNew = true;
            }
        }

        if (seededNew) {
            return sequenceRepository.findByClientIdAndOrgId(clientId, orgId);
        }
        
        return existing;
    }

    @Transactional
    public DocumentSequence createSequence(DocumentSequence data) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = getEffectiveOrgId();
        
        // UPSERT Logic: If it exists, update it. If not, create it.
        return sequenceRepository.findByClientIdAndOrgIdAndDocumentType(clientId, orgId, data.getDocumentType())
                .map(existing -> updateSequence(existing.getId(), data))
                .orElseGet(() -> {
                    data.setClientId(clientId);
                    data.setOrgId(orgId);
                    if (data.getNextNumber() == null) data.setNextNumber(1L);
                    if (data.getPaddingLength() == null) data.setPaddingLength(7);
                    if (data.getIsActive() == null) data.setIsActive(true);
                    return sequenceRepository.save(data);
                });
    }

    @Transactional
    public DocumentSequence updateSequence(UUID id, DocumentSequence updateData) {
        DocumentSequence seq = sequenceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Sequence rule not found"));

        // Copy fields carefully
        if (updateData.getPrefix() != null) seq.setPrefix(updateData.getPrefix());
        if (updateData.getSuffix() != null) seq.setSuffix(updateData.getSuffix());
        if (updateData.getPaddingLength() != null) seq.setPaddingLength(updateData.getPaddingLength());
        if (updateData.getIsActive() != null) seq.setIsActive(updateData.getIsActive());
        if (updateData.getNextNumber() != null) seq.setNextNumber(updateData.getNextNumber());

        return sequenceRepository.saveAndFlush(seq);
    }

    private String resolvePlaceholders(String value, UUID orgId, UUID clientId) {
        if (value == null) return "";
        String year = String.valueOf(LocalDateTime.now().getYear());
        String branchCode = orgId == null
                ? "ORG"
                : organizationRepository.findByIdAndClientId(orgId, clientId)
                        .map(Organization::getBranchCode)
                        .orElse("HQ");
        return value.replace("{YYYY}", year).replace("{BRANCH_CODE}", branchCode);
    }

    private String format(String prefix, String suffix, Integer paddingLength, long number) {
        int padding = paddingLength == null ? 7 : paddingLength;
        String formattedNum = String.format("%0" + padding + "d", number);
        return (prefix == null ? "" : prefix) + formattedNum + (suffix == null ? "" : suffix);
    }

    private boolean documentNumberExists(DocumentType type, UUID clientId, UUID orgId, String documentNo) {
        return switch (type) {
            case SALE_ORDER, QUOTATION, PURCHASE_ORDER, DELIVERY_CHALLAN ->
                    orderRepository.existsByClientIdAndOrgIdAndOrderNo(clientId, orgId, documentNo);
            case EXPENSE ->
                    expenseRepository.existsByClientIdAndOrgIdAndExpenseNo(clientId, orgId, documentNo);
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

    private UUID getEffectiveOrgId() {
        return branchContext.requireWriteOrgId(TenantContext.getCurrentOrg());
    }
}
