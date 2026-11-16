package com.restaurant.pos.costing.command;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.TaxType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Works out the net unit cost of a purchase line for stock costing:
 * price after the line discount, always without purchase tax.
 */
@Component
public class PurchaseCostResolver {

    public BigDecimal netUnitCost(Order order, OrderLine line) {
        return netUnitCost(line);
    }

    public static BigDecimal netUnitCost(OrderLine line) {
        if (line == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = line.getQuantity() != null && line.getQuantity().signum() > 0
                ? line.getQuantity() : BigDecimal.ONE;
        BigDecimal discount = nz(line.getDiscountAmount());

        BigDecimal net;
        if (line.getUnitPriceExTax() != null && line.getUnitPriceExTax().signum() > 0) {
            net = line.getUnitPriceExTax().multiply(qty).subtract(discount);
        } else {
            net = nz(line.getUnitPrice()).multiply(qty).subtract(discount);
            if (line.getTaxType() == TaxType.INCLUSIVE) {
                net = net.subtract(nz(line.getTaxAmount()));
            }
        }

        if (net.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return net.divide(qty, CostingService.COST_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
