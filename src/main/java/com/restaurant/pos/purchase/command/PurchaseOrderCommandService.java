package com.restaurant.pos.purchase.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchase.domain.PurchaseOrder;
import com.restaurant.pos.purchase.dto.BulkPurchaseOrderResponseDto;
import com.restaurant.pos.purchase.event.PurchaseOrderCompletedEvent;
import com.restaurant.pos.purchase.event.PurchaseOrderVoidedEvent;
import com.restaurant.pos.purchase.mapper.PurchaseOrderDtoMapper;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CQRS Command Service for Purchase Orders.
 * Enforces server-side financial total recalculation, domain entity state transitions,
 * and publishes domain events (PurchaseOrderCompletedEvent, PurchaseOrderVoidedEvent) for decoupled listeners.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderCommandService {

    private final OrderRepository orderRepository;
    private final DocumentSequenceService documentSequenceService;
    private final PurchaseOrderDtoMapper purchaseOrderDtoMapper;
    private final BranchContextService branchContext;
    private final ApplicationEventPublisher eventPublisher;
    private final com.restaurant.pos.inventory.service.InventoryService inventoryService;
    private final com.restaurant.pos.invoice.repository.InvoiceRepository invoiceRepository;
    private final com.restaurant.pos.order.repository.PaymentRepository paymentRepository;

    /**
     * Creates and saves a new Purchase Order with server-side total recalculation.
     */
    @Transactional
    public OrderResponseDto createPurchaseOrder(CreatePurchaseOrderCommand command, String idempotencyKey) {
        validateCreateCommand(command);

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.requireWriteOrgId(command.getOrgId());
        String sourceRef = StringUtils.hasText(idempotencyKey) ? idempotencyKey : command.getSourceLocalRef();

        // Idempotency check: Return existing order if identical sourceLocalRef was already processed
        if (StringUtils.hasText(sourceRef)) {
            var existingOpt = orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(
                    clientId, orgId, sourceRef, "VOID");
            if (existingOpt.isPresent()) {
                log.info("Idempotent hit: returning existing Purchase Order {} for sourceRef {}", 
                        existingOpt.get().getOrderNo(), sourceRef);
                return purchaseOrderDtoMapper.toResponseDto(existingOpt.get());
            }
        }

        String orderNo = documentSequenceService.generateNextSequence(DocumentType.PURCHASE_ORDER, orgId);

        Order mappedOrder = purchaseOrderDtoMapper.toEntity(command);
        PurchaseOrder purchaseOrder;
        if (mappedOrder instanceof PurchaseOrder po) {
            purchaseOrder = po;
        } else {
            purchaseOrder = new PurchaseOrder();
            purchaseOrder.setVendorId(mappedOrder.getVendorId());
            purchaseOrder.setWarehouseId(mappedOrder.getWarehouseId());
            purchaseOrder.setLines(mappedOrder.getLines());
            purchaseOrder.setPaymentMethod(mappedOrder.getPaymentMethod());
            purchaseOrder.setPaymentSplits(mappedOrder.getPaymentSplits());
        }

        purchaseOrder.setClientId(clientId);
        purchaseOrder.setOrgId(orgId);
        purchaseOrder.setOrderNo(orderNo);
        purchaseOrder.setOrderType(OrderType.PURCHASE);
        purchaseOrder.setSourceLocalRef(sourceRef);

        if (!StringUtils.hasText(purchaseOrder.getOrderStatus())) {
            purchaseOrder.setOrderStatus(OrderStatus.DRAFT.name());
        }
        if (command.getIsReceived() != null) {
            purchaseOrder.setIsReceived(command.getIsReceived());
        } else {
            purchaseOrder.setIsReceived(OrderStatus.COMPLETED.name().equalsIgnoreCase(purchaseOrder.getOrderStatus()));
        }
        if (!StringUtils.hasText(purchaseOrder.getPaymentStatus())) {
            purchaseOrder.setPaymentStatus("PENDING");
        }

        if (purchaseOrder.getWarehouseId() == null && orgId != null) {
            inventoryService.findDefaultWarehouse(clientId, orgId)
                    .ifPresent(dw -> purchaseOrder.setWarehouseId(dw.getId()));
        }

        // Link child lines to parent order
        if (purchaseOrder.getLines() != null) {
            for (OrderLine line : purchaseOrder.getLines()) {
                line.setOrder(purchaseOrder);
            }
        }

        // SERVER-SIDE RECALCULATION: Never trust client totals!
        recalculateTotals(purchaseOrder);

        log.info("Creating Purchase Order | orderNo={} | vendorId={} | warehouseId={} | grandTotal={} | lineCount={}",
                orderNo, command.getVendorId(), command.getWarehouseId(),
                purchaseOrder.getGrandTotal(), purchaseOrder.getLines() != null ? purchaseOrder.getLines().size() : 0);

        Order savedOrder = orderRepository.save(purchaseOrder);

        // Fire domain event for completed/confirmed orders (vendor bill generation, accounting, etc.)
        if (OrderStatus.COMPLETED.name().equalsIgnoreCase(savedOrder.getOrderStatus()) || OrderStatus.CONFIRMED.name().equalsIgnoreCase(savedOrder.getOrderStatus())) {
            eventPublisher.publishEvent(new PurchaseOrderCompletedEvent(savedOrder));
        }

        return purchaseOrderDtoMapper.toResponseDto(savedOrder);
    }

    /**
     * Updates an existing Purchase Order using domain methods.
     * If the order was previously completed, publishes a void event to reverse stock and void prior invoices/payments.
     */
    @Transactional
    public OrderResponseDto updatePurchaseOrder(UUID orderId, UpdatePurchaseOrderCommand command) {
        UUID clientId = TenantContext.getCurrentTenant();

        Order existingEntity = orderRepository.findByIdAndClientId(orderId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found: " + orderId));

        if (existingEntity.getOrderType() != null && existingEntity.getOrderType() != OrderType.PURCHASE) {
            throw new BusinessException("Order " + orderId + " is not a Purchase Order");
        }

        boolean isReceived = Boolean.TRUE.equals(existingEntity.getIsReceived());

        if (isReceived) {
            throw new BusinessException("Received Purchase Orders cannot be edited. You can void the order if needed.");
        }

        boolean isDraft = OrderStatus.DRAFT.name().equalsIgnoreCase(existingEntity.getOrderStatus());

        // IF DRAFT: Update existing entity in place directly without creating _VOID_ revision snapshots
        if (isDraft) {
            if (command.getVendorId() != null) existingEntity.setVendorId(command.getVendorId());
            if (command.getWarehouseId() != null) existingEntity.setWarehouseId(command.getWarehouseId());
            if (command.getPaymentStatus() != null) existingEntity.setPaymentStatus(command.getPaymentStatus());
            if (command.getPaymentMethod() != null) existingEntity.setPaymentMethod(command.getPaymentMethod());
            if (command.getPaymentSplits() != null) existingEntity.setPaymentSplits(command.getPaymentSplits());
            if (command.getReference() != null) existingEntity.setReference(command.getReference());
            if (command.getDescription() != null) existingEntity.setDescription(command.getDescription());
            if (command.getIsReceived() != null) existingEntity.setIsReceived(command.getIsReceived());
            if (command.getOrderDate() != null) existingEntity.setOrderDate(command.getOrderDate());

            if (command.getLines() != null) {
                Order updatedLinesEntity = purchaseOrderDtoMapper.toEntity(command);
                existingEntity.getLines().clear();
                if (updatedLinesEntity.getLines() != null) {
                    for (OrderLine line : updatedLinesEntity.getLines()) {
                        line.setOrder(existingEntity);
                        existingEntity.getLines().add(line);
                    }
                }
            }

            recalculateTotals(existingEntity);

            if (StringUtils.hasText(command.getOrderStatus())) {
                String targetStatus = command.getOrderStatus().toUpperCase();
                if (OrderStatus.COMPLETED.name().equals(targetStatus)) {
                    if (!StringUtils.hasText(existingEntity.getPaymentMethod())) {
                        throw new BusinessException("Payment method is required to complete a Purchase Order.");
                    }
                    if (existingEntity instanceof PurchaseOrder po) {
                        po.complete();
                    } else {
                        existingEntity.setOrderStatus(OrderStatus.COMPLETED.name());
                    }
                    existingEntity.setIsReceived(true);
                } else if (OrderStatus.CONFIRMED.name().equals(targetStatus) && existingEntity instanceof PurchaseOrder po) {
                    po.confirm();
                } else if ((OrderStatus.VOID.name().equals(targetStatus) || OrderStatus.CANCELLED.name().equals(targetStatus)) && existingEntity instanceof PurchaseOrder po) {
                    po.voidOrder();
                } else {
                    existingEntity.setOrderStatus(targetStatus);
                }
            }

            validatePurchaseOrder(existingEntity);

            log.info("Updating DRAFT Purchase Order in place | id={} | orderNo={} | status={} | grandTotal={}",
                    orderId, existingEntity.getOrderNo(), existingEntity.getOrderStatus(), existingEntity.getGrandTotal());

            Order savedOrder = orderRepository.save(existingEntity);
            publishCompletedEventIfNeeded(savedOrder);

            return purchaseOrderDtoMapper.toResponseDto(savedOrder);
        }

        // NON-DRAFT (e.g. CONFIRMED): Create _VOID_ revision snapshot and new revised entity
        String originalOrderNo = existingEntity.getOrderNo();
        if (originalOrderNo != null && originalOrderNo.contains("_VOID_")) {
            originalOrderNo = originalOrderNo.substring(0, originalOrderNo.indexOf("_VOID_"));
        }
        int currentRev = existingEntity.getRevisionNumber() != null ? existingEntity.getRevisionNumber() : 0;
        UUID rootOrderId = existingEntity.getOriginalOrderId() != null ? existingEntity.getOriginalOrderId() : existingEntity.getId();

        existingEntity.setOrderNo(originalOrderNo + "_VOID_" + currentRev);
        existingEntity.setOrderStatus(OrderStatus.VOID.name());
        existingEntity.setIsactive("N");
        orderRepository.saveAndFlush(existingEntity);

        // Void linked invoice (Vendor Bill) and payment for the old revision
        List<com.restaurant.pos.invoice.domain.Invoice> invoices = invoiceRepository.findByOrderId(orderId);
        if (invoices != null && !invoices.isEmpty()) {
            for (com.restaurant.pos.invoice.domain.Invoice inv : invoices) {
                inv.setInvoiceNo(inv.getInvoiceNo() + "_VOID_" + currentRev);
                inv.setStatus(OrderStatus.VOID.name());
                inv.setDocStatus(OrderStatus.VOID.name());
                inv.setIsactive("N");
                invoiceRepository.saveAndFlush(inv);
            }
        }
        List<com.restaurant.pos.order.domain.Payment> payments = paymentRepository.findByOrderId(orderId);
        if (payments != null && !payments.isEmpty()) {
            for (com.restaurant.pos.order.domain.Payment pay : payments) {
                pay.setReferenceNo((pay.getReferenceNo() != null ? pay.getReferenceNo() : "PAY") + "_VOID_" + currentRev);
                pay.setDocStatus(OrderStatus.VOID.name());
                pay.setIsactive("N");
                paymentRepository.saveAndFlush(pay);
            }
        }

        // Create NEW revised PurchaseOrder entity retaining original orderNo
        PurchaseOrder newPo = new PurchaseOrder();
        newPo.setOrderType(OrderType.PURCHASE);
        newPo.setClientId(clientId);
        newPo.setOrgId(existingEntity.getOrgId());
        newPo.setOrderNo(originalOrderNo);
        newPo.setOriginalOrderId(rootOrderId);
        newPo.setRevisionNumber(currentRev + 1);
        newPo.setIsactive("Y");

        // Copy / update fields from command or existing
        newPo.setVendorId(command.getVendorId() != null ? command.getVendorId() : existingEntity.getVendorId());
        newPo.setWarehouseId(command.getWarehouseId() != null ? command.getWarehouseId() : existingEntity.getWarehouseId());
        newPo.setPaymentStatus(command.getPaymentStatus() != null ? command.getPaymentStatus() : existingEntity.getPaymentStatus());
        newPo.setPaymentMethod(command.getPaymentMethod() != null ? command.getPaymentMethod() : existingEntity.getPaymentMethod());
        newPo.setPaymentSplits(command.getPaymentSplits() != null ? command.getPaymentSplits() : existingEntity.getPaymentSplits());
        newPo.setReference(command.getReference() != null ? command.getReference() : existingEntity.getReference());
        newPo.setDescription(command.getDescription() != null ? command.getDescription() : existingEntity.getDescription());
        newPo.setIsReceived(command.getIsReceived() != null ? command.getIsReceived() : existingEntity.getIsReceived());
        newPo.setOrderDate(command.getOrderDate() != null ? command.getOrderDate() : existingEntity.getOrderDate());

        String targetStatus = StringUtils.hasText(command.getOrderStatus()) ? command.getOrderStatus().toUpperCase() : existingEntity.getOrderStatus();
        if (OrderStatus.COMPLETED.name().equals(targetStatus)) {
            if (!StringUtils.hasText(newPo.getPaymentMethod())) {
                throw new BusinessException("Payment method is required to complete a Purchase Order.");
            }
            newPo.complete();
            newPo.setIsReceived(true);
        } else {
            newPo.setOrderStatus(targetStatus);
        }

        // Copy or replace lines
        if (command.getLines() != null && !command.getLines().isEmpty()) {
            Order updatedLinesEntity = purchaseOrderDtoMapper.toEntity(command);
            if (updatedLinesEntity.getLines() != null) {
                for (OrderLine line : updatedLinesEntity.getLines()) {
                    newPo.addLine(line);
                }
            }
        } else if (existingEntity.getLines() != null) {
            for (OrderLine oldLine : existingEntity.getLines()) {
                newPo.addLine(copyOrderLine(oldLine));
            }
        }

        recalculateTotals(newPo);
        validatePurchaseOrder(newPo);

        log.info("Creating Revised Purchase Order | newId={} | orderNo={} | rev={} | grandTotal={}",
                newPo.getId(), newPo.getOrderNo(), newPo.getRevisionNumber(), newPo.getGrandTotal());

        Order savedOrder = orderRepository.save(newPo);
        publishCompletedEventIfNeeded(savedOrder);

        return purchaseOrderDtoMapper.toResponseDto(savedOrder);
    }

    /**
     * Voids / cancels an existing Purchase Order and publishes PurchaseOrderVoidedEvent.
     */
    @Transactional
    public OrderResponseDto voidPurchaseOrder(UUID orderId) {
        UUID clientId = TenantContext.getCurrentTenant();

        Order existingEntity = orderRepository.findByIdAndClientId(orderId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found: " + orderId));

        if (existingEntity.getOrderType() != null && existingEntity.getOrderType() != OrderType.PURCHASE) {
            throw new BusinessException("Order " + orderId + " is not a Purchase Order");
        }

        if (existingEntity instanceof PurchaseOrder po) {
            po.voidOrder();
        } else {
            if ("VOID".equalsIgnoreCase(existingEntity.getOrderStatus()) || "CANCELLED".equalsIgnoreCase(existingEntity.getOrderStatus())) {
                throw new BusinessException("Purchase Order is already voided");
            }
            existingEntity.setOrderStatus(OrderStatus.VOID.name());
        }

        log.info("Voiding Purchase Order | id={} | orderNo={}", orderId, existingEntity.getOrderNo());
        Order savedOrder = orderRepository.save(existingEntity);

        // Publish decoupled domain event to void linked invoices, payments, and reverse stock
        eventPublisher.publishEvent(new PurchaseOrderVoidedEvent(savedOrder));

        return purchaseOrderDtoMapper.toResponseDto(savedOrder);
    }

    /**
     * Receives an existing Purchase Order (marks COMPLETED, triggers stock intake & vendor bill).
     */
    @Transactional
    public OrderResponseDto receivePurchaseOrder(UUID orderId) {
        UUID clientId = TenantContext.getCurrentTenant();

        Order existingEntity = orderRepository.findByIdAndClientId(orderId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found: " + orderId));

        if (existingEntity.getOrderType() != null && existingEntity.getOrderType() != OrderType.PURCHASE) {
            throw new BusinessException("Order " + orderId + " is not a Purchase Order");
        }

        boolean isAlreadyReceived = Boolean.TRUE.equals(existingEntity.getIsReceived()) || OrderStatus.COMPLETED.name().equalsIgnoreCase(existingEntity.getOrderStatus());
        if (isAlreadyReceived) {
            throw new BusinessException("Purchase Order " + existingEntity.getOrderNo() + " is already received");
        }

        existingEntity.setIsReceived(true);
        if (existingEntity instanceof PurchaseOrder po) {
            po.complete();
        } else {
            existingEntity.setOrderStatus(OrderStatus.COMPLETED.name());
        }

        log.info("Receiving Purchase Order | id={} | orderNo={} | isReceived=true | status=COMPLETED", orderId, existingEntity.getOrderNo());
        Order savedOrder = orderRepository.save(existingEntity);

        // Fire domain event when transitioning to COMPLETED (triggers stock intake and vendor bill creation)
        eventPublisher.publishEvent(new PurchaseOrderCompletedEvent(savedOrder));

        return purchaseOrderDtoMapper.toResponseDto(savedOrder);
    }

    /**
     * Voids / cancels multiple Purchase Orders in a single bulk operation.
     */
    @Transactional
    public BulkPurchaseOrderResponseDto bulkVoidPurchaseOrders(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return BulkPurchaseOrderResponseDto.builder()
                    .totalRequested(0)
                    .processedCount(0)
                    .build();
        }

        UUID clientId = TenantContext.getCurrentTenant();
        List<UUID> successfulIds = new ArrayList<>();
        List<OrderResponseDto> processedOrders = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (UUID orderId : orderIds) {
            try {
                Order existingEntity = orderRepository.findByIdAndClientId(orderId, clientId)
                        .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found: " + orderId));

                if (existingEntity.getOrderType() != null && existingEntity.getOrderType() != OrderType.PURCHASE) {
                    throw new BusinessException("Order " + orderId + " is not a Purchase Order");
                }

                if (existingEntity instanceof PurchaseOrder po) {
                    po.voidOrder();
                } else {
                    if ("VOID".equalsIgnoreCase(existingEntity.getOrderStatus()) || "CANCELLED".equalsIgnoreCase(existingEntity.getOrderStatus())) {
                        throw new BusinessException("Purchase Order " + existingEntity.getOrderNo() + " is already voided");
                    }
                    existingEntity.setOrderStatus(OrderStatus.VOID.name());
                }

                existingEntity.setIsReceived(false);
                Order savedOrder = orderRepository.save(existingEntity);
                eventPublisher.publishEvent(new PurchaseOrderVoidedEvent(savedOrder));

                successfulIds.add(orderId);
                processedOrders.add(purchaseOrderDtoMapper.toResponseDto(savedOrder));
            } catch (Exception e) {
                log.warn("Bulk void failed for PO ID {}: {}", orderId, e.getMessage());
                errors.add("PO " + orderId + ": " + e.getMessage());
            }
        }

        log.info("Bulk void completed | requested={} | processed={}", orderIds.size(), successfulIds.size());
        return BulkPurchaseOrderResponseDto.builder()
                .totalRequested(orderIds.size())
                .processedCount(successfulIds.size())
                .successfulIds(successfulIds)
                .processedOrders(processedOrders)
                .errors(errors)
                .build();
    }

    /**
     * Receives/completes multiple Purchase Orders in a single bulk operation.
     */
    @Transactional
    public BulkPurchaseOrderResponseDto bulkReceivePurchaseOrders(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return BulkPurchaseOrderResponseDto.builder()
                    .totalRequested(0)
                    .processedCount(0)
                    .build();
        }

        UUID clientId = TenantContext.getCurrentTenant();
        List<UUID> successfulIds = new ArrayList<>();
        List<OrderResponseDto> processedOrders = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (UUID orderId : orderIds) {
            try {
                Order existingEntity = orderRepository.findByIdAndClientId(orderId, clientId)
                        .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found: " + orderId));

                if (existingEntity.getOrderType() != null && existingEntity.getOrderType() != OrderType.PURCHASE) {
                    throw new BusinessException("Order " + orderId + " is not a Purchase Order");
                }

                boolean wasCompleted = OrderStatus.COMPLETED.name().equalsIgnoreCase(existingEntity.getOrderStatus());
                if (wasCompleted) {
                    throw new BusinessException("Purchase Order " + existingEntity.getOrderNo() + " is already received/completed");
                }

                if (!StringUtils.hasText(existingEntity.getPaymentMethod())) {
                    throw new BusinessException("Payment method must be specified before receiving Purchase Order " + existingEntity.getOrderNo());
                }

                if (existingEntity instanceof PurchaseOrder po) {
                    po.complete();
                } else {
                    existingEntity.setOrderStatus(OrderStatus.COMPLETED.name());
                }

                existingEntity.setIsReceived(true);
                Order savedOrder = orderRepository.save(existingEntity);
                eventPublisher.publishEvent(new PurchaseOrderCompletedEvent(savedOrder));

                successfulIds.add(orderId);
                processedOrders.add(purchaseOrderDtoMapper.toResponseDto(savedOrder));
            } catch (Exception e) {
                log.warn("Bulk receive failed for PO ID {}: {}", orderId, e.getMessage());
                errors.add("PO " + orderId + ": " + e.getMessage());
            }
        }

        log.info("Bulk receive completed | requested={} | processed={}", orderIds.size(), successfulIds.size());
        return BulkPurchaseOrderResponseDto.builder()
                .totalRequested(orderIds.size())
                .processedCount(successfulIds.size())
                .successfulIds(successfulIds)
                .processedOrders(processedOrders)
                .errors(errors)
                .build();
    }

    private void validateCreateCommand(CreatePurchaseOrderCommand command) {
        if (command.getVendorId() == null) {
            throw new BusinessException("Vendor must be specified for a Purchase Order");
        }
        if (command.getWarehouseId() == null) {
            throw new BusinessException("Warehouse must be specified for a Purchase Order");
        }
        if (command.getLines() == null || command.getLines().isEmpty()) {
            throw new BusinessException("Purchase Order must have at least one line item");
        }
        boolean isDraft = command.getOrderStatus() == null || OrderStatus.DRAFT.name().equalsIgnoreCase(command.getOrderStatus());
        if (!isDraft && !StringUtils.hasText(command.getPaymentMethod())) {
            throw new BusinessException("Payment method must be specified for a Purchase Order");
        }
    }

    /**
     * Recalculates all line totals and order totals server-side.
     * Line Total = (Quantity x Unit Price) - Discount + Tax Amount
     * Grand Total = Sum(Line Totals)
     */
    private void recalculateTotals(Order order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setTotalAmount(BigDecimal.ZERO);
            order.setTotalTaxAmount(BigDecimal.ZERO);
            order.setTotalDiscountAmount(BigDecimal.ZERO);
            order.setGrandTotal(BigDecimal.ZERO);
            return;
        }

        BigDecimal calculatedSubtotal = BigDecimal.ZERO;
        BigDecimal calculatedTax = BigDecimal.ZERO;
        BigDecimal calculatedDiscount = BigDecimal.ZERO;
        BigDecimal calculatedGrandTotal = BigDecimal.ZERO;

        for (OrderLine line : order.getLines()) {
            BigDecimal qty = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO;
            BigDecimal price = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal lineGross = qty.multiply(price);

            BigDecimal discount = line.getDiscountAmount() != null ? line.getDiscountAmount() : BigDecimal.ZERO;
            BigDecimal tax = line.getTaxAmount() != null ? line.getTaxAmount() : BigDecimal.ZERO;
            BigDecimal lineTotal = lineGross.subtract(discount).add(tax);

            line.setGrossLineAmount(lineGross);
            line.setLineTotal(lineTotal);

            calculatedSubtotal = calculatedSubtotal.add(lineGross);
            calculatedTax = calculatedTax.add(tax);
            calculatedDiscount = calculatedDiscount.add(discount);
            calculatedGrandTotal = calculatedGrandTotal.add(lineTotal);
        }

        order.setTotalAmount(calculatedSubtotal);
        order.setTotalTaxAmount(calculatedTax);
        order.setTotalDiscountAmount(calculatedDiscount);
        order.setGrandTotal(calculatedGrandTotal);
    }

    private void validatePurchaseOrder(Order order) {
        if (order.getVendorId() == null) {
            throw new BusinessException("Vendor / Supplier is required for a Purchase Order.");
        }
        if (order.getWarehouseId() == null) {
            throw new BusinessException("Receiving warehouse is required for a Purchase Order.");
        }
        if (order.getLines() == null || order.getLines().isEmpty()) {
            throw new BusinessException("Purchase Order must contain at least one line item.");
        }
    }

    private OrderLine copyOrderLine(OrderLine oldLine) {
        OrderLine newLine = new OrderLine();
        newLine.setProductId(oldLine.getProductId());
        newLine.setVariantId(oldLine.getVariantId());
        newLine.setProductName(oldLine.getProductName());
        newLine.setCategoryName(oldLine.getCategoryName());
        newLine.setUnitOfMeasure(oldLine.getUnitOfMeasure());
        newLine.setUomPrecision(oldLine.getUomPrecision());
        newLine.setQuantity(oldLine.getQuantity());
        newLine.setUnitPrice(oldLine.getUnitPrice());
        newLine.setTaxRate(oldLine.getTaxRate());
        newLine.setTaxAmount(oldLine.getTaxAmount());
        newLine.setDiscountAmount(oldLine.getDiscountAmount());
        newLine.setLineTotal(oldLine.getLineTotal());
        return newLine;
    }

    private void publishCompletedEventIfNeeded(Order savedOrder) {
        boolean isNowCompletedOrReceived = OrderStatus.COMPLETED.name().equalsIgnoreCase(savedOrder.getOrderStatus())
                || Boolean.TRUE.equals(savedOrder.getIsReceived());
        if (isNowCompletedOrReceived) {
            eventPublisher.publishEvent(new PurchaseOrderCompletedEvent(savedOrder));
        }
    }
}
