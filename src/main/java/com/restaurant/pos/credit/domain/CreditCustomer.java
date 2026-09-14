package com.restaurant.pos.credit.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "credit_customers")
public class CreditCustomer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "linked_customer_id")
    private UUID linkedCustomerId;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String email;

    @Builder.Default
    @Column(length = 20, nullable = false)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(name = "credit_limit", precision = 15, scale = 2, nullable = false)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "opening_balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    /** Denormalized outstanding balance (opening_balance + active invoice dues), maintained by DB trigger on invoices. */
    @Builder.Default
    @Column(name = "current_balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1, nullable = false)
    private String isactive = "Y";
}
