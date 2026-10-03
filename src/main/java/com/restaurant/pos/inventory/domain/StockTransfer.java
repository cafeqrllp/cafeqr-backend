package com.restaurant.pos.inventory.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "stock_transfers")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class StockTransfer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "source_warehouse_id", nullable = false)
    private UUID sourceWarehouseId;

    @Column(name = "dest_warehouse_id", nullable = false)
    private UUID destWarehouseId;

    @Column(name = "transfer_number", unique = true, nullable = false, length = 50)
    private String transferNumber;

    @Builder.Default
    @Column(name = "transfer_date")
    private LocalDateTime transferDate = LocalDateTime.now();

    @Builder.Default
    @Column(length = 20)
    private String status = "DRAFT"; // DRAFT, IN_TRANSIT, COMPLETED, CANCELLED

    @Builder.Default
    @Column(name = "was_in_transit")
    private Boolean wasInTransit = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Transient
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Transient
    @Builder.Default
    @JsonProperty("confirmStockWarning")
    private Boolean confirmStockWarning = false;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @BatchSize(size = 50)
    @Builder.Default
    private List<StockTransferLine> lines = new ArrayList<>();

    public void addLine(StockTransferLine line) {
        lines.add(line);
        line.setTransfer(this);
    }
}
