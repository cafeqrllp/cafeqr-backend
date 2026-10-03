package com.restaurant.pos.inventory.command;

import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockAdjustmentLine;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.inventory.domain.StockTransfer;
import com.restaurant.pos.inventory.domain.StockTransferLine;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import com.restaurant.pos.inventory.repository.StockSnapshotRepository;
import com.restaurant.pos.inventory.repository.StockTransferRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryCommandService {

    private final StockLedgerRepository stockLedgerRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;
    private final AccountingPostingService accountingPostingService;
    private final BranchContextService branchContext;
    private final DocumentSequenceService documentSequenceService;
    private final WarehouseRepository warehouseRepository;
    private final com.restaurant.pos.product.repository.ProductRepository productRepository;
    private final com.restaurant.pos.common.service.SystemConfigurationService configurationService;

    public Optional<StockSnapshot> findStockSnapshot(UUID warehouseId, UUID productId, UUID variantId) {
        java.util.List<StockSnapshot> list = stockSnapshotRepository
                .findByWarehouseIdAndProductIdAndVariantId(warehouseId, productId, variantId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        if (list.size() == 1) {
            return Optional.of(list.get(0));
        }
        StockSnapshot primary = list.get(0);
        BigDecimal totalQty = BigDecimal.ZERO;
        for (StockSnapshot s : list) {
            if (s.getCurrentQuantity() != null) {
                totalQty = totalQty.add(s.getCurrentQuantity());
            }
        }
        primary.setCurrentQuantity(totalQty);
        stockSnapshotRepository.save(primary);
        for (int i = 1; i < list.size(); i++) {
            try {
                stockSnapshotRepository.delete(list.get(i));
            } catch (Exception ignored) {}
        }
        return Optional.of(primary);
    }

    public void updateStock(UUID warehouseId, UUID productId, UUID variantId, BigDecimal quantityChange,
                            String transactionType, UUID referenceId, BigDecimal unitCost) {
        updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost, null, null);
    }

    public void updateStock(
            UUID warehouseId,
            UUID productId,
            UUID variantId,
            BigDecimal quantityChange,
            String transactionType,
            UUID referenceId,
            BigDecimal unitCost,
            UUID explicitOrgId) {
        updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost, explicitOrgId, null);
    }

    public void updateStock(
            UUID warehouseId,
            UUID productId,
            UUID variantId,
            BigDecimal quantityChange,
            String transactionType,
            UUID referenceId,
            BigDecimal unitCost,
            UUID explicitOrgId,
            UUID explicitClientId) {

        UUID resolvedClientId = explicitClientId != null
                ? explicitClientId
                : TenantContext.getCurrentTenant();
        UUID resolvedOrgId = explicitOrgId != null
                ? explicitOrgId
                : TenantContext.getCurrentOrg();

        if (resolvedClientId == null && warehouseId != null) {
            resolvedClientId = warehouseRepository.findById(warehouseId)
                    .map(Warehouse::getClientId)
                    .orElse(null);
        }
        if (resolvedOrgId == null && warehouseId != null) {
            resolvedOrgId = warehouseRepository.findById(warehouseId)
                    .map(Warehouse::getOrgId)
                    .orElse(null);
        }

        final UUID clientId = resolvedClientId;
        final UUID orgId = resolvedOrgId;

        StockSnapshot snapshot = findStockSnapshot(warehouseId, productId, variantId)
                .orElseGet(() -> StockSnapshot.builder()
                        .clientId(clientId)
                        .orgId(orgId)
                        .warehouseId(warehouseId)
                        .productId(productId)
                        .variantId(variantId)
                        .currentQuantity(BigDecimal.ZERO)
                        .build());

        if (snapshot.getClientId() == null && clientId != null) {
            snapshot.setClientId(clientId);
        }
        if (snapshot.getOrgId() == null && orgId != null) {
            snapshot.setOrgId(orgId);
        }

        BigDecimal newBalance = snapshot.getCurrentQuantity().add(quantityChange);

        snapshot.setCurrentQuantity(newBalance);
        snapshot.setLastUpdated(LocalDateTime.now());
        stockSnapshotRepository.save(snapshot);

        StockLedger ledger = StockLedger.builder()
                .clientId(clientId)
                .orgId(orgId)
                .warehouseId(warehouseId)
                .productId(productId)
                .variantId(variantId)
                .transactionType(transactionType)
                .referenceId(referenceId)
                .quantityChange(quantityChange)
                .balanceAfterTransaction(newBalance)
                .unitCost(unitCost)
                .createdBy(SecurityUtils.getCurrentUserId())
                .build();

        stockLedgerRepository.save(ledger);
    }

    public StockAdjustment saveAdjustment(StockAdjustment adjustment) {
        UUID clientId = TenantContext.getCurrentTenant();

        StockAdjustment existing = null;
        if (adjustment.getId() != null) {
            existing = stockAdjustmentRepository.findById(adjustment.getId()).orElse(null);
        }
        String previousStatus = existing != null ? existing.getStatus() : null;

        UUID orgId = branchContext.requireWriteOrgId(adjustment.getOrgId());
        
        adjustment.setClientId(clientId);
        adjustment.setOrgId(orgId);

        if (adjustment.getId() == null || adjustment.getAdjustmentNumber() == null 
                || adjustment.getAdjustmentNumber().matches("^ADJ-\\d+$")
                || "Auto Generated".equalsIgnoreCase(adjustment.getAdjustmentNumber())) {
            adjustment.setAdjustmentNumber(documentSequenceService.generateNextSequence(DocumentType.STOCK_ADJUSTMENT, orgId));
        }

        if (adjustment.getLines() != null) {
            for (StockAdjustmentLine line : adjustment.getLines()) {
                line.setAdjustment(adjustment);
                if (line.getIsActive() == null) {
                    line.setIsActive("Y");
                }
            }
        }

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);

        if ("COMPLETED".equalsIgnoreCase(saved.getStatus()) && saved.getLines() != null) {
            boolean alreadyCompleted = "COMPLETED".equalsIgnoreCase(previousStatus);
            if (!alreadyCompleted) {
                for (StockAdjustmentLine line : saved.getLines()) {
                    updateStock(saved.getWarehouseId(), line.getProductId(), line.getVariantId(), 
                            line.getQuantityChange(), "ADJUSTMENT", saved.getId(), line.getUnitCost(),
                            saved.getOrgId(), saved.getClientId());
                }
            }
        }
        
        accountingPostingService.postStockAdjustment(saved);
        return saved;
    }



    public StockTransfer saveTransfer(StockTransfer transfer) {
        UUID clientId = TenantContext.getCurrentTenant();

        StockTransfer existing = null;
        if (transfer.getId() != null) {
            existing = stockTransferRepository.findById(transfer.getId()).orElse(null);
        }
        String previousStatus = existing != null ? existing.getStatus() : null;

        UUID orgId;
        if (existing != null) {
            UUID activeOrgId = branchContext.requireWriteOrgId(null);
            
            Warehouse sourceWh = warehouseRepository.findById(existing.getSourceWarehouseId()).orElse(null);
            Warehouse destWh = warehouseRepository.findById(existing.getDestWarehouseId()).orElse(null);
            UUID sourceOrgId = sourceWh != null && sourceWh.getOrgId() != null ? sourceWh.getOrgId() : existing.getOrgId();
            UUID destOrgId = destWh != null && destWh.getOrgId() != null ? destWh.getOrgId() : existing.getOrgId();
            
            if (!SecurityUtils.isSuperAdmin() && activeOrgId != null 
                    && !activeOrgId.equals(sourceOrgId) && !activeOrgId.equals(destOrgId)) {
                throw new com.restaurant.pos.common.exception.BusinessException(
                        "Selected branch does not match the source or destination branch of this transfer."
                );
            }
            orgId = existing.getOrgId();
            transfer.setOrgId(orgId);
        } else {
            orgId = branchContext.requireWriteOrgId(transfer.getOrgId());
            transfer.setOrgId(orgId);
        }

        transfer.setClientId(clientId);

        if ("IN_TRANSIT".equalsIgnoreCase(transfer.getStatus())) {
            transfer.setWasInTransit(true);
        } else if (existing != null && Boolean.TRUE.equals(existing.getWasInTransit())) {
            transfer.setWasInTransit(true);
        }

        if (transfer.getId() == null || transfer.getTransferNumber() == null 
                || transfer.getTransferNumber().matches("^TRF-\\d+$")
                || "Auto Generated".equalsIgnoreCase(transfer.getTransferNumber())) {
            transfer.setTransferNumber(documentSequenceService.generateNextSequence(DocumentType.STOCK_TRANSFER, orgId));
        }

        if (transfer.getLines() != null) {
            for (StockTransferLine line : transfer.getLines()) {
                line.setTransfer(transfer);
                if (line.getIsActive() == null) {
                    line.setIsActive("Y");
                }
                if (line.getProductId() != null) {
                    com.restaurant.pos.product.domain.Product product = productRepository.findById(line.getProductId()).orElse(null);
                    if (product != null) {
                        if (product.getOrgId() != null && !new UUID(0L, 0L).equals(product.getOrgId())) {
                            throw new com.restaurant.pos.common.exception.BusinessException(
                                    "Product '" + product.getName() + "' is branch-specific and cannot be transferred across branches."
                            );
                        }
                        if (product.getRecipeLines() != null && !product.getRecipeLines().isEmpty()) {
                            throw new com.restaurant.pos.common.exception.BusinessException(
                                    "Product '" + product.getName() + "' contains ingredients and cannot be transferred."
                            );
                        }
                    }
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        if (transfer.getLines() != null && !transfer.getLines().isEmpty()) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(transfer.getStatus());
            boolean alreadyCompleted = "COMPLETED".equalsIgnoreCase(previousStatus);
            boolean checkStock = !alreadyCompleted;
            if (checkStock) {
                com.restaurant.pos.common.dto.ConfigurationDto sysConfig = configurationService.getConfigurationForClientAndBranch(clientId, orgId);
                boolean isInventoryOn = sysConfig != null && sysConfig.isInventoryEnabled();
                String transferPolicy = isInventoryOn && sysConfig.getNonStockTransferPolicy() != null
                        ? sysConfig.getNonStockTransferPolicy()
                        : "NONE";
                boolean isBlock = "BLOCK".equalsIgnoreCase(transferPolicy);
                boolean isWarn = "WARNING".equalsIgnoreCase(transferPolicy) || "WARN".equalsIgnoreCase(transferPolicy);

                if (isBlock || isWarn) {
                    for (StockTransferLine line : transfer.getLines()) {
                        StockSnapshot snapshot = findStockSnapshot(transfer.getSourceWarehouseId(), line.getProductId(), line.getVariantId())
                                .orElse(null);
                        if (snapshot == null && line.getVariantId() != null) {
                            snapshot = findStockSnapshot(transfer.getSourceWarehouseId(), line.getProductId(), null)
                                    .orElse(null);
                        }
                        BigDecimal available = snapshot != null && snapshot.getCurrentQuantity() != null ? snapshot.getCurrentQuantity() : BigDecimal.ZERO;
                        String itemName = line.getProductName();
                        if (itemName == null || itemName.isBlank()) {
                            itemName = productRepository.findById(line.getProductId()).map(com.restaurant.pos.product.domain.Product::getName).orElse("Item");
                        }
                        BigDecimal reqQty = line.getTransferQuantity() != null ? line.getTransferQuantity() : BigDecimal.ONE;

                        if (available.compareTo(BigDecimal.ZERO) <= 0) {
                            String msg = "'" + itemName + "' is out of stock (Available: 0, Required: " + reqQty + ").";
                            if (isBlock && !isDraft) {
                                throw new com.restaurant.pos.common.exception.BusinessException(
                                        "Cannot transfer non-stock item: " + msg
                                );
                            } else if (isWarn) {
                                warnings.add(msg);
                            }
                        } else if (reqQty.compareTo(available) > 0) {
                            String msg = "Transfer quantity (" + reqQty + ") for '" + itemName + "' exceeds available stock (" + available + ").";
                            if (isBlock && !isDraft) {
                                throw new com.restaurant.pos.common.exception.BusinessException(msg);
                            } else if (isWarn) {
                                warnings.add(msg);
                            }
                        }
                    }

                    if (!warnings.isEmpty() && isWarn && !isDraft && !Boolean.TRUE.equals(transfer.getConfirmStockWarning())) {
                        throw new com.restaurant.pos.common.exception.StockWarningException(warnings);
                    }
                }
            }
        }

        if (!warnings.isEmpty()) {
            transfer.setWarnings(warnings);
        }

        StockTransfer saved = stockTransferRepository.save(transfer);
        if (!warnings.isEmpty()) {
            saved.setWarnings(warnings);
        }

        if ("COMPLETED".equalsIgnoreCase(saved.getStatus())) {
            boolean alreadyCompleted = "COMPLETED".equalsIgnoreCase(previousStatus);
            if (!alreadyCompleted) {
                Warehouse sourceWh = warehouseRepository.findById(saved.getSourceWarehouseId()).orElse(null);
                Warehouse destWh = warehouseRepository.findById(saved.getDestWarehouseId()).orElse(null);
                UUID sourceOrgId = sourceWh != null && sourceWh.getOrgId() != null ? sourceWh.getOrgId() : orgId;
                UUID destOrgId = destWh != null && destWh.getOrgId() != null ? destWh.getOrgId() : orgId;

                for (StockTransferLine line : saved.getLines()) {
                    updateStock(saved.getSourceWarehouseId(), line.getProductId(), line.getVariantId(), 
                            line.getTransferQuantity().negate(), "TRANSFER_OUT", saved.getId(), BigDecimal.ZERO, sourceOrgId);
                    
                    updateStock(saved.getDestWarehouseId(), line.getProductId(), line.getVariantId(), 
                            line.getTransferQuantity(), "TRANSFER_IN", saved.getId(), BigDecimal.ZERO, destOrgId);
                }
            }
        } else if ("CANCELLED".equalsIgnoreCase(saved.getStatus()) || "VOIDED".equalsIgnoreCase(saved.getStatus())) {
            boolean wasAlreadyCompleted = "COMPLETED".equalsIgnoreCase(previousStatus);
            if (wasAlreadyCompleted) {
                Warehouse sourceWh = warehouseRepository.findById(saved.getSourceWarehouseId()).orElse(null);
                Warehouse destWh = warehouseRepository.findById(saved.getDestWarehouseId()).orElse(null);
                UUID sourceOrgId = sourceWh != null && sourceWh.getOrgId() != null ? sourceWh.getOrgId() : orgId;
                UUID destOrgId = destWh != null && destWh.getOrgId() != null ? destWh.getOrgId() : orgId;

                for (StockTransferLine line : saved.getLines()) {
                    updateStock(saved.getSourceWarehouseId(), line.getProductId(), line.getVariantId(), 
                            line.getTransferQuantity(), "TRANSFER_VOID_REVERT", saved.getId(), BigDecimal.ZERO, sourceOrgId);
                    
                    updateStock(saved.getDestWarehouseId(), line.getProductId(), line.getVariantId(), 
                            line.getTransferQuantity().negate(), "TRANSFER_VOID_REVERT", saved.getId(), BigDecimal.ZERO, destOrgId);
                }
            }
        }

        return saved;
    }
}
