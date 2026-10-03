package com.restaurant.pos.purchase.mapper;

import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.purchase.command.CreatePurchaseOrderCommand;
import com.restaurant.pos.purchase.command.UpdatePurchaseOrderCommand;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.purchase.domain.PurchaseOrder;
import com.restaurant.pos.purchase.dto.PurchaseOrderSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maps between Purchase CQRS commands/DTOs and domain entities.
 * Resolves human-readable user display names (createdBy / updatedBy) and linked Vendor Bill numbers.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderDtoMapper {

    private final UserRepository userRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * Converts a CreatePurchaseOrderCommand into a PurchaseOrder domain entity.
     */
    public Order toEntity(CreatePurchaseOrderCommand command) {
        PurchaseOrder order = new PurchaseOrder();
        order.setOrderType(OrderType.PURCHASE);
        order.setVendorId(command.getVendorId());
        order.setWarehouseId(command.getWarehouseId());

        if (command.getOrgId() != null) {
            order.setOrgId(command.getOrgId());
        }
        if (command.getOrderDate() != null) {
            order.setOrderDate(command.getOrderDate());
        }
        if (command.getOrderStatus() != null) {
            order.setOrderStatus(command.getOrderStatus());
        }
        if (command.getPaymentStatus() != null) {
            order.setPaymentStatus(command.getPaymentStatus());
        }

        order.setPaymentMethod(command.getPaymentMethod());
        order.setPaymentSplits(command.getPaymentSplits());
        order.setReference(command.getReference());
        order.setDescription(command.getDescription());
        order.setSourceLocalRef(command.getSourceLocalRef());

        if (command.getTotalAmount() != null)
            order.setTotalAmount(command.getTotalAmount());
        if (command.getTotalTaxAmount() != null)
            order.setTotalTaxAmount(command.getTotalTaxAmount());
        if (command.getTotalDiscountAmount() != null)
            order.setTotalDiscountAmount(command.getTotalDiscountAmount());
        if (command.getGrandTotal() != null)
            order.setGrandTotal(command.getGrandTotal());

        // Map line items
        List<OrderLine> lines = new ArrayList<>();
        if (command.getLines() != null) {
            for (CreatePurchaseOrderCommand.PurchaseOrderLineCommand lineCmd : command.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineCmd.getProductId());
                line.setVariantId(lineCmd.getVariantId());
                line.setProductName(lineCmd.getProductName());
                line.setQuantity(lineCmd.getQuantity());
                line.setUnitPrice(lineCmd.getUnitPrice());
                line.setUnitOfMeasure(lineCmd.getUnitOfMeasure());
                line.setTaxRate(lineCmd.getTaxRate() != null ? lineCmd.getTaxRate() : BigDecimal.ZERO);
                line.setTaxAmount(lineCmd.getTaxAmount() != null ? lineCmd.getTaxAmount() : BigDecimal.ZERO);
                line.setDiscountAmount(lineCmd.getDiscountAmount() != null ? lineCmd.getDiscountAmount() : BigDecimal.ZERO);
                line.setLineTotal(lineCmd.getLineTotal() != null ? lineCmd.getLineTotal() : BigDecimal.ZERO);
                line.setDescription(lineCmd.getDescription());
                line.setOrder(order);
                lines.add(line);
            }
        }
        order.setLines(lines);

        return order;
    }

    /**
     * Converts an UpdatePurchaseOrderCommand into a partial Order entity for merging.
     */
    public Order toEntity(UpdatePurchaseOrderCommand command) {
        Order order = new Order();
        order.setOrderType(OrderType.PURCHASE);
        order.setVendorId(command.getVendorId());
        order.setWarehouseId(command.getWarehouseId());

        if (command.getOrderDate() != null) {
            order.setOrderDate(command.getOrderDate());
        }
        if (command.getOrderStatus() != null) {
            order.setOrderStatus(command.getOrderStatus());
        }
        if (command.getPaymentStatus() != null) {
            order.setPaymentStatus(command.getPaymentStatus());
        }

        order.setPaymentMethod(command.getPaymentMethod());
        order.setReference(command.getReference());
        order.setDescription(command.getDescription());

        if (command.getTotalAmount() != null)
            order.setTotalAmount(command.getTotalAmount());
        if (command.getTotalTaxAmount() != null)
            order.setTotalTaxAmount(command.getTotalTaxAmount());
        if (command.getTotalDiscountAmount() != null)
            order.setTotalDiscountAmount(command.getTotalDiscountAmount());
        if (command.getGrandTotal() != null)
            order.setGrandTotal(command.getGrandTotal());

        if (command.getLines() != null) {
            List<OrderLine> lines = new ArrayList<>();
            for (CreatePurchaseOrderCommand.PurchaseOrderLineCommand lineCmd : command.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineCmd.getProductId());
                line.setVariantId(lineCmd.getVariantId());
                line.setProductName(lineCmd.getProductName());
                line.setQuantity(lineCmd.getQuantity());
                line.setUnitPrice(lineCmd.getUnitPrice());
                line.setUnitOfMeasure(lineCmd.getUnitOfMeasure());
                line.setTaxRate(lineCmd.getTaxRate() != null ? lineCmd.getTaxRate() : BigDecimal.ZERO);
                line.setTaxAmount(lineCmd.getTaxAmount() != null ? lineCmd.getTaxAmount() : BigDecimal.ZERO);
                line.setDiscountAmount(lineCmd.getDiscountAmount() != null ? lineCmd.getDiscountAmount() : BigDecimal.ZERO);
                line.setLineTotal(lineCmd.getLineTotal() != null ? lineCmd.getLineTotal() : BigDecimal.ZERO);
                line.setDescription(lineCmd.getDescription());
                line.setOrder(order);
                lines.add(line);
            }
            order.setLines(lines);
        }

        return order;
    }

    /**
     * Converts a PurchaseOrder domain entity to OrderResponseDto.
     * Resolves human-readable user names for createdBy and updatedBy, as well as linked vendor bill invoice numbers.
     */
    public OrderResponseDto toResponseDto(Order order) {
        if (order == null) return null;

        String createdByName = resolveUserDisplayName(order.getCreatedBy());
        String updatedByName = resolveUserDisplayName(order.getUpdatedBy());
        String invoiceNo = resolveInvoiceNo(order.getId(), order.getClientId());

        OrderResponseDto.OrderResponseDtoBuilder builder = OrderResponseDto.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .orderType(order.getOrderType())
                .orderStatus(order.getOrderStatus())
                .isReceived(order.getIsReceived() != null ? order.getIsReceived() : OrderStatus.COMPLETED.name().equalsIgnoreCase(order.getOrderStatus()))
                .paymentStatus(order.getPaymentStatus())
                .vendorId(order.getVendorId())
                .warehouseId(order.getWarehouseId())
                .orgId(order.getOrgId())
                .terminalId(order.getTerminalId())
                .orderSource(order.getOrderSource())
                .syncOrigin(order.getSyncOrigin())
                .currencyId(order.getCurrencyId())
                .orderDate(order.getOrderDate())
                .grossAmount(order.getGrossAmount())
                .totalAmount(order.getTotalAmount())
                .totalTaxAmount(order.getTotalTaxAmount())
                .totalDiscountAmount(order.getTotalDiscountAmount())
                .roundOffAmount(order.getRoundOffAmount())
                .grandTotal(order.getGrandTotal())
                .paymentMethod(order.getPaymentMethod())
                .reference(order.getReference())
                .description(order.getDescription())
                .invoiceNo(invoiceNo)
                .createdBy(createdByName)
                .updatedBy(updatedByName)
                .revisionNumber(order.getRevisionNumber())
                .originalOrderId(order.getOriginalOrderId())
                .createdAt(order.getCreatedAt() != null ? order.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null)
                .updatedAt(order.getUpdatedAt() != null ? order.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC) : null);

        if (order.getLines() != null) {
            List<OrderResponseDto.OrderLineResponseDto> lineDtos = order.getLines().stream()
                    .map(line -> OrderResponseDto.OrderLineResponseDto.builder()
                            .id(line.getId())
                            .productId(line.getProductId())
                            .variantId(line.getVariantId())
                            .productName(line.getProductName())
                            .quantity(line.getQuantity())
                            .unitPrice(line.getUnitPrice())
                            .unitOfMeasure(line.getUnitOfMeasure())
                            .taxRate(line.getTaxRate())
                            .taxAmount(line.getTaxAmount())
                            .discountAmount(line.getDiscountAmount())
                            .lineTotal(line.getLineTotal())
                            .description(line.getDescription())
                            .build())
                    .toList();
            builder.lines(lineDtos);
        }

        return builder.build();
    }

    private String resolveUserDisplayName(String uidStr) {
        if (uidStr == null || uidStr.isBlank() || "SYSTEM".equalsIgnoreCase(uidStr)) {
            return "SYSTEM";
        }
        try {
            UUID userId = UUID.fromString(uidStr);
            return userRepository.findById(userId)
                    .map(u -> {
                        String first = u.getFirstName() != null ? u.getFirstName() : "";
                        String last = u.getLastName() != null && !u.getLastName().isBlank() ? " " + u.getLastName() : "";
                        String fullName = (first + last).trim();
                        return !fullName.isBlank() ? fullName : (u.getEmail() != null ? u.getEmail() : uidStr);
                    })
                    .orElse(uidStr);
        } catch (Exception e) {
            return uidStr;
        }
    }

    private String resolveInvoiceNo(UUID orderId, UUID clientId) {
        if (orderId == null || clientId == null) return null;
        try {
            UUID orgId = TenantContext.getCurrentOrg();
            if (orgId != null) {
                return invoiceRepository.findByOrderIdAndClientIdAndOrgId(orderId, clientId, orgId)
                        .map(com.restaurant.pos.invoice.domain.Invoice::getInvoiceNo)
                        .orElseGet(() -> invoiceRepository.findByOrderIdAndClientId(orderId, clientId)
                                .map(com.restaurant.pos.invoice.domain.Invoice::getInvoiceNo)
                                .orElse(null));
            }
            return invoiceRepository.findByOrderIdAndClientId(orderId, clientId)
                    .map(com.restaurant.pos.invoice.domain.Invoice::getInvoiceNo)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maps an Order domain entity to a lightweight PurchaseOrderSummaryDto.
     * Reads column properties directly from the Order entity — does NOT trigger lazy collections or sub-queries.
     */
    public PurchaseOrderSummaryDto toSummaryDto(Order order) {
        if (order == null) return null;
        return PurchaseOrderSummaryDto.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .orderStatus(order.getOrderStatus())
                .isReceived(order.getIsReceived())
                .paymentStatus(order.getPaymentStatus())
                .paymentMethod(order.getPaymentMethod())
                .vendorId(order.getVendorId())
                .warehouseId(order.getWarehouseId())
                .orgId(order.getOrgId())
                .orderDate(order.getOrderDate())
                .grandTotal(order.getGrandTotal())
                .totalTaxAmount(order.getTotalTaxAmount())
                .totalDiscountAmount(order.getTotalDiscountAmount())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt() != null ? order.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null)
                .reference(order.getReference())
                .revisionNumber(order.getRevisionNumber())
                .originalOrderId(order.getOriginalOrderId())
                .build();
    }
}

