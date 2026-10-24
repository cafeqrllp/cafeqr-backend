package com.restaurant.pos.subscription.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_payments")
@Getter
@Setter
@Builder
@AllArgsConstructor
public class SubscriptionPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "payment_id", unique = true, nullable = false)
    private String paymentId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    private long amount;

    private String currency;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public SubscriptionPayment() {
        super();
        this.currency = "INR";
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (currency == null) {
            currency = "INR";
        }
    }

    public static class SubscriptionPaymentBuilder {
        private String currency = "INR";
    }
}
