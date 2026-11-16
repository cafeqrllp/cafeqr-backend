package com.restaurant.pos.purchase.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasePaymentStatusTest {

    @Test
    void voidedOrCancelledPoIsNeverPaid() {
        assertThat(PurchasePaymentStatus.effective("VOID", "PAID")).isEqualTo("VOID");
        assertThat(PurchasePaymentStatus.effective("CANCELLED", "PAID")).isEqualTo("VOID");
        assertThat(PurchasePaymentStatus.effective("void", "PARTIAL")).isEqualTo("VOID");
    }

    @Test
    void draftHasNoPaymentYet() {
        assertThat(PurchasePaymentStatus.effective("DRAFT", "PAID")).isEqualTo("PENDING");
        assertThat(PurchasePaymentStatus.effective("DRAFT", "PARTIAL")).isEqualTo("PENDING");
    }

    @Test
    void activePoKeepsItsStatus() {
        assertThat(PurchasePaymentStatus.effective("COMPLETED", "PAID")).isEqualTo("PAID");
        assertThat(PurchasePaymentStatus.effective("CONFIRMED", "partial")).isEqualTo("PARTIAL");
        assertThat(PurchasePaymentStatus.effective("COMPLETED", null)).isEqualTo("PENDING");
    }

    @Test
    void voidingPurchaseOrderMarksPaymentVoid() {
        PurchaseOrder po = new PurchaseOrder();
        po.setOrderStatus("COMPLETED");
        po.setPaymentStatus("PAID");

        po.voidOrder();

        assertThat(po.getOrderStatus()).isEqualTo("VOID");
        assertThat(po.getPaymentStatus()).isEqualTo("VOID");
    }
}
