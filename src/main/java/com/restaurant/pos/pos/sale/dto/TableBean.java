package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Dedicated restaurant table projection bean for the POS Sales Screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableBean {
    private UUID id;
    private String tableNumber;
    private String name;
    private Integer seatingCapacity;
    private String floor;
    private String section;
    private String shape;
    private String status;
    private Integer displayOrder;
}
