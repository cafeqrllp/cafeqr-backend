package com.restaurant.pos.pos.sale.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight projection interface for POS Customer quick lookup.
 * Directly maps native SQL query results without loading heavyweight customer entities.
 */
public interface PosCustomerSummaryView {
    UUID getId();
    String getName();
    String getPhone();
    String getEmail();
    String getAddress();
    String getGstNumber();
    String getCustomerCategory();
    Integer getLoyaltyPoints();
    BigDecimal getCreditLimit();
    BigDecimal getBalance();
}
