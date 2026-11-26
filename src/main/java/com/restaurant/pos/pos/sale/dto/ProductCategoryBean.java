package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dedicated product category projection bean for the POS Sales Screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryBean {
    private String id;
    private String name;
    private String color;
    private Integer sortOrder;
    private Integer sequenceNo;
    private BigDecimal qtySold;
    private BigDecimal salesAmount;
    private LocalDateTime lastSoldAt;
    private Integer productCount;
}
