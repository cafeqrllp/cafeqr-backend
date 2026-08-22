package com.restaurant.pos.purchase.listener;

import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceLine;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchase.event.PurchaseOrderCompletedEvent;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Event Listener for Purchase Order completion.
 * Generates the corresponding Vendor Bill (c_invoice / c_invoiceline)
 * and records the Outbound Payment transaction when the purchase is paid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderVendorBillListener {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final com.restaurant.pos.order.repository.PaymentSplitRepository paymentSplitRepository;
    private final DocumentSequenceService documentSequenceService;

    @EventListener
    @Transactional
    public void onPurchaseOrderCompleted(PurchaseOrderCompletedEvent event) {
        Order purchaseOrder = event.purchaseOrder();

        try {
            var existingBillOpt = invoiceRepository.findByOrderIdAndClientId(purchaseOrder.getId(), purchaseOrder.getClientId());
            Invoice vendorBill;
            String billNo;
            if (existingBillOpt.isPresent()) {
                vendorBill = existingBillOpt.get();
                billNo = vendorBill.getInvoiceNo();
            } else {
                billNo = documentSequenceService.generateNextSequence(DocumentType.VENDOR_BILL, purchaseOrder.getOrgId());
                vendorBill = new Invoice();
                vendorBill.setInvoiceType(InvoiceType.VENDOR_BILL);
                vendorBill.setOrderId(purchaseOrder.getId());
                vendorBill.setInvoiceNo(billNo);
                vendorBill.setClientId(purchaseOrder.getClientId());
                vendorBill.setOrgId(purchaseOrder.getOrgId());
            }

            boolean isPaid = "PAID".equalsIgnoreCase(purchaseOrder.getPaymentStatus());
            boolean isCredit = "CREDIT".equalsIgnoreCase(purchaseOrder.getPaymentMethod());
            BigDecimal grandTotal = purchaseOrder.getGrandTotal() != null ? purchaseOrder.getGrandTotal() : BigDecimal.ZERO;

            vendorBill.setVendorId(purchaseOrder.getVendorId());
            vendorBill.setCreditCustomerId(null);
            vendorBill.setInvoiceDate(LocalDateTime.now());
            vendorBill.setTotalAmount(grandTotal);
            vendorBill.setAmountDue(isPaid && !isCredit ? BigDecimal.ZERO : grandTotal);
            vendorBill.setStatus(isPaid && !isCredit ? "PAID" : "COMPLETED");
            vendorBill.setDocStatus("COMPLETED");
            vendorBill.setIsPaid(isPaid && !isCredit);
            vendorBill.setIsactive("Y");
            vendorBill.setGrossAmount(purchaseOrder.getGrandTotal());
            vendorBill.setTotalTaxAmount(purchaseOrder.getTotalTaxAmount());
            vendorBill.setTotalDiscountAmount(purchaseOrder.getTotalDiscountAmount());

            if (vendorBill.getLines() == null) {
                vendorBill.setLines(new ArrayList<>());
            } else {
                vendorBill.getLines().clear();
            }

            if (purchaseOrder.getLines() != null) {
                for (OrderLine ol : purchaseOrder.getLines()) {
                    InvoiceLine il = InvoiceLine.builder()
                            .productId(ol.getProductId())
                            .variantId(ol.getVariantId())
                            .productName(ol.getProductName())
                            .quantity(ol.getQuantity())
                            .unitPrice(ol.getUnitPrice())
                            .taxRate(ol.getTaxRate())
                            .taxAmount(ol.getTaxAmount())
                            .discountAmount(ol.getDiscountAmount())
                            .lineTotal(ol.getLineTotal())
                            .unitOfMeasure(ol.getUnitOfMeasure())
                            .build();
                    vendorBill.addLine(il);
                }
            }

            Invoice savedBill = invoiceRepository.save(vendorBill);
            log.info("EventListener: Generated/Updated Vendor Bill | billNo={} | orderId={}", billNo, purchaseOrder.getId());

            // If the purchase order is paid (not credit), record/update the OUTBOUND Payment transaction
            if (isPaid && !isCredit && grandTotal.compareTo(BigDecimal.ZERO) > 0) {
                String paymentNo;
                Payment payment;
                var existingPayments = paymentRepository.findByOrderId(purchaseOrder.getId());
                if (existingPayments != null && !existingPayments.isEmpty()) {
                    payment = existingPayments.get(0);
                    paymentNo = payment.getReferenceNo();
                } else {
                    paymentNo = documentSequenceService.generateNextSequence(DocumentType.OUTBOUND_PAYMENT, purchaseOrder.getOrgId());
                    payment = new Payment();
                    payment.setPaymentType(PaymentType.OUTBOUND);
                    payment.setOrderId(purchaseOrder.getId());
                    payment.setReferenceNo(paymentNo);
                    payment.setClientId(purchaseOrder.getClientId());
                    payment.setOrgId(purchaseOrder.getOrgId());
                }

                payment.setInvoiceId(savedBill.getId());
                payment.setCreditCustomerId(purchaseOrder.getVendorId());
                payment.setPaymentDate(LocalDateTime.now());
                payment.setPaymentMethod(purchaseOrder.getPaymentMethod() != null ? purchaseOrder.getPaymentMethod().toUpperCase() : "CASH");
                payment.setAmountPaid(grandTotal);
                payment.setChangeGiven(BigDecimal.ZERO);
                payment.setDescription("Purchase Payment for PO " + purchaseOrder.getOrderNo());
                payment.setDocStatus("COMPLETED");
                payment.setIsactive("Y");

                Payment savedPayment = paymentRepository.save(payment);
                log.info("EventListener: Recorded/Updated OUTBOUND Payment | paymentNo={} | method={} | amount={}",
                        paymentNo, payment.getPaymentMethod(), grandTotal);

                // If MIXED, record payment splits
                if ("MIXED".equalsIgnoreCase(payment.getPaymentMethod()) && purchaseOrder.getPaymentSplits() != null && !purchaseOrder.getPaymentSplits().isEmpty()) {
                    var existingSplits = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(savedPayment.getId());
                    if (existingSplits != null && !existingSplits.isEmpty()) {
                        paymentSplitRepository.deleteAll(existingSplits);
                    }
                    List<com.restaurant.pos.order.domain.PaymentSplit> splits = new ArrayList<>();
                    for (var sp : purchaseOrder.getPaymentSplits()) {
                        if (sp != null && sp.getAmount() != null && sp.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                            var split = com.restaurant.pos.order.domain.PaymentSplit.builder()
                                    .paymentId(savedPayment.getId())
                                    .paymentMethod(sp.getPaymentMethod() != null ? sp.getPaymentMethod().toUpperCase() : "CASH")
                                    .amount(sp.getAmount())
                                    .referenceNo(paymentNo)
                                    .build();
                            split.setClientId(purchaseOrder.getClientId());
                            split.setOrgId(purchaseOrder.getOrgId());
                            splits.add(split);
                        }
                    }
                    if (!splits.isEmpty()) {
                        paymentSplitRepository.saveAll(splits);
                        log.info("EventListener: Recorded {} Payment Splits for PO {}", splits.size(), purchaseOrder.getOrderNo());
                    }
                }
            } else {
                // If paymentMethod is CREDIT or status changed to UNPAID, void any prior outbound payment
                var existingPayments = paymentRepository.findByOrderId(purchaseOrder.getId());
                for (Payment p : existingPayments) {
                    p.setDocStatus("VOID");
                    p.setIsactive("N");
                    paymentRepository.save(p);
                }
            }
        } catch (Exception e) {
            log.error("EventListener: Failed to generate Vendor Bill / Payment for PO {}: {}", purchaseOrder.getOrderNo(), e.getMessage(), e);
        }
    }
}
