package com.restaurant.pos.purchase.domain;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.OrderType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Domain Entity for Purchase Orders (Money Flow OUT).
 * Enforces domain state transitions (complete, confirm, cancel).
 */
@Entity
@Getter
@Setter
@ToString(callSuper = true)
@DiscriminatorValue("PURCHASE")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class PurchaseOrder extends Order {

    public PurchaseOrder() {
        super();
        this.setOrderType(OrderType.PURCHASE);
    }

    // ─────────────────────────────────────────────────────────────
    // Domain State Transition Methods
    // ─────────────────────────────────────────────────────────────

    /**
     * Advances the Purchase Order status to CONFIRMED.
     */
    public void confirm() {
        if ("CANCELLED".equalsIgnoreCase(this.getOrderStatus()) || "VOID".equalsIgnoreCase(this.getOrderStatus())) {
            throw new BusinessException("Cannot confirm a cancelled or void Purchase Order");
        }
        this.setOrderStatus(OrderStatus.CONFIRMED.name());
    }

    /**
     * Advances the Purchase Order status to COMPLETED.
     */
    public void complete() {
        if ("VOID".equalsIgnoreCase(this.getOrderStatus()) || "CANCELLED".equalsIgnoreCase(this.getOrderStatus())) {
            throw new BusinessException("Cannot complete a voided Purchase Order");
        }
        this.setOrderStatus(OrderStatus.COMPLETED.name());
    }

    /**
     * Voids the Purchase Order.
     */
    public void voidOrder() {
        if ("VOID".equalsIgnoreCase(this.getOrderStatus()) || "CANCELLED".equalsIgnoreCase(this.getOrderStatus())) {
            throw new BusinessException("Purchase Order is already voided");
        }
        this.setOrderStatus(OrderStatus.VOID.name());
        // Its payments are voided with it, so it no longer counts as paid.
        this.setPaymentStatus(com.restaurant.pos.order.domain.PaymentStatus.VOID.name());
    }

    public void cancel() {
        voidOrder();
    }

    public boolean isCompleted() {
        return OrderStatus.COMPLETED.name().equalsIgnoreCase(this.getOrderStatus());
    }
}
