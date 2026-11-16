package com.restaurant.pos.purchase.domain;

import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.PaymentStatus;

/**
 * The payment status a Purchase Order should be shown and counted with.
 *
 * The stored payment_status is what the user picked on the form. It can be stale:
 * voided/cancelled POs kept PAID after their payments were voided, and a DRAFT can be
 * saved as PAID although no payment is recorded until the PO is completed.
 * The stored value is left alone for drafts (it is the intended payment applied on
 * completion); only reports and lists use this effective value.
 */
public final class PurchasePaymentStatus {

    private PurchasePaymentStatus() {
    }

    public static String effective(String orderStatus, String paymentStatus) {
        String st = orderStatus == null ? "" : orderStatus.toUpperCase();
        String pay = paymentStatus == null || paymentStatus.isBlank()
                ? PaymentStatus.PENDING.name()
                : paymentStatus.toUpperCase();

        if (OrderStatus.VOID.name().equals(st) || "CANCELLED".equals(st)) {
            return PaymentStatus.VOID.name();
        }
        if (OrderStatus.DRAFT.name().equals(st)) {
            return PaymentStatus.PENDING.name();
        }
        return pay;
    }
}
