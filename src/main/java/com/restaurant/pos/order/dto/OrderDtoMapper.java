package com.restaurant.pos.order.dto;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.purchasing.domain.PurchaseOrder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderDtoMapper {

    public OrderResponseDto toResponseDto(Order order) {
        if (order == null)
            return null;

        List<OrderResponseDto.OrderLineResponseDto> lines = null;
        if (order.getLines() != null) {
            lines = order.getLines().stream()
                    .map(this::toLineResponseDto)
                    .toList();
        }

        return OrderResponseDto.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .orderType(order.getOrderType())
                .orderStatus(order.getOrderStatus())
                .paymentStatus(order.getPaymentStatus())
                .orderSource(order.getOrderSource())
                .tableId(order.getTableId())
                .tableNumber(order.getTableNumber())
                .warehouseId(order.getWarehouseId())
                .vendorId(order.getVendorId())
                .currencyId(order.getCurrencyId())
                .orderDate(order.getOrderDate())
                .totalTaxAmount(order.getTotalTaxAmount())
                .totalDiscountAmount(order.getTotalDiscountAmount())
                .totalAmount(order.getTotalAmount())
                .grandTotal(order.getGrandTotal())
                .fulfillmentType(order.getFulfillmentType())
                .description(order.getDescription())
                .reference(order.getReference())
                .customers(order.getCustomers())
                .isCredit(order.getIsCredit())
                .creditCustomerId(order.getCreditCustomerId())
                .invoiceNo(order.getInvoiceNo())
                .paymentNo(order.getPaymentNo())
                .paymentMethod(order.getPaymentMethod())
                .lines(lines)
                .build();
    }

    public OrderResponseDto.OrderLineResponseDto toLineResponseDto(OrderLine line) {
        if (line == null)
            return null;
        return OrderResponseDto.OrderLineResponseDto.builder()
                .id(line.getId())
                .productId(line.getProductId())
                .variantId(line.getVariantId())
                .productName(line.getProductName())
                .unitOfMeasure(line.getUnitOfMeasure())
                .quantity(line.getQuantity())
                .unitPrice(line.getUnitPrice())
                .taxRate(line.getTaxRate())
                .taxAmount(line.getTaxAmount())
                .discountAmount(line.getDiscountAmount())
                .lineTotal(line.getLineTotal())
                .build();
    }

    public Order toEntity(CreateOrderRequest request) {
        if (request == null)
            return null;
        Order order;
        if (request.getOrderType() == OrderType.PURCHASE) {
            order = new PurchaseOrder();
        } else {
            order = new Order();
        }
        order.setOrderType(request.getOrderType());
        order.setOrderNo(request.getOrderNo());
        order.setOfflineInvoiceNo(request.getOfflineInvoiceNo());
        order.setOfflinePaymentNo(request.getOfflinePaymentNo());
        order.setTableId(request.getTableId());
        order.setTableNumber(request.getTableNumber());
        order.setWarehouseId(request.getWarehouseId());
        order.setVendorId(request.getVendorId());
        order.setPricelistId(request.getPricelistId());
        order.setCurrencyId(request.getCurrencyId());
        order.setFulfillmentType(request.getFulfillmentType() != null ? request.getFulfillmentType() : "DINE_IN");
        order.setCustomerIds(request.getCustomerIds());
        order.setIsCredit(Boolean.TRUE.equals(request.getIsCredit()));
        order.setCreditCustomerId(request.getCreditCustomerId());
        order.setDescription(request.getDescription());
        order.setReference(request.getReference());
        order.setPaymentMethod(request.getPaymentMethod());
        if (request.getOrderDate() != null) {
            order.setOrderDate(request.getOrderDate());
        }
        if (request.getOrderStatus() != null && !request.getOrderStatus().isBlank()) {
            order.setOrderStatus(request.getOrderStatus());
        }
        if (request.getPaymentStatus() != null && !request.getPaymentStatus().isBlank()) {
            order.setPaymentStatus(request.getPaymentStatus());
        }
        if (request.getTotalAmount() != null) {
            order.setTotalAmount(request.getTotalAmount());
        }
        if (request.getTotalTaxAmount() != null) {
            order.setTotalTaxAmount(request.getTotalTaxAmount());
        }
        if (request.getTotalDiscountAmount() != null) {
            order.setTotalDiscountAmount(request.getTotalDiscountAmount());
        }
        if (request.getGrandTotal() != null) {
            order.setGrandTotal(request.getGrandTotal());
        }

        if (request.getLines() != null) {
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                order.addLine(line);
            }
        }
        return order;
    }

    public Order applyUpdates(Order existing, UpdateOrderRequest request) {
        if (request == null)
            return existing;
        if (request.getTableId() != null)
            existing.setTableId(request.getTableId());
        if (request.getTableNumber() != null)
            existing.setTableNumber(request.getTableNumber());
        if (request.getWarehouseId() != null)
            existing.setWarehouseId(request.getWarehouseId());
        if (request.getVendorId() != null)
            existing.setVendorId(request.getVendorId());
        if (request.getOrderStatus() != null)
            existing.setOrderStatus(request.getOrderStatus());
        if (request.getPaymentStatus() != null)
            existing.setPaymentStatus(request.getPaymentStatus());
        if (request.getDescription() != null)
            existing.setDescription(request.getDescription());
        if (request.getReference() != null)
            existing.setReference(request.getReference());
        if (request.getPaymentMethod() != null)
            existing.setPaymentMethod(request.getPaymentMethod());
        if (request.getFulfillmentType() != null)
            existing.setFulfillmentType(request.getFulfillmentType());
        if (request.getCustomerIds() != null)
            existing.setCustomerIds(request.getCustomerIds());
        if (request.getIsCredit() != null)
            existing.setIsCredit(request.getIsCredit());
        if (request.getCreditCustomerId() != null)
            existing.setCreditCustomerId(request.getCreditCustomerId());

        if (request.getLines() != null) {
            existing.getLines().clear();
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                existing.addLine(line);
            }
        }
        return existing;
    }

    public Order toEntity(UpdateOrderRequest request) {
        if (request == null)
            return null;
        Order order = new Order();
        order.setTableId(request.getTableId());
        order.setTableNumber(request.getTableNumber());
        order.setWarehouseId(request.getWarehouseId());
        order.setVendorId(request.getVendorId());
        order.setOrderStatus(request.getOrderStatus());
        order.setPaymentStatus(request.getPaymentStatus());
        order.setDescription(request.getDescription());
        order.setReference(request.getReference());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setFulfillmentType(request.getFulfillmentType());
        order.setCustomerIds(request.getCustomerIds());
        order.setIsCredit(request.getIsCredit());
        order.setCreditCustomerId(request.getCreditCustomerId());

        if (request.getLines() != null) {
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                order.addLine(line);
            }
        }
        return order;
    }
}
