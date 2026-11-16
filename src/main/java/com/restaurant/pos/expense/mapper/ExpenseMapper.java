package com.restaurant.pos.expense.mapper;

import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.dto.ExpenseResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Maps between Expense entities and their DTOs.
 */
@Component
public class ExpenseMapper {

    public ExpenseResponse toExpenseResponse(Expense expense, String categoryName) {
        return ExpenseResponse.builder()
                .id(expense.getId())
                .referenceNumber(expense.getExpenseNo())
                .categoryId(expense.getCategoryId())
                .categoryName(categoryName)
                .expenseDate(expense.getExpenseDate())
                .amount(expense.getAmount())
                .description(expense.getDescription())
                .paymentMethod(expense.getPaymentMethod())
                .active(expense.isActive())
                .orgId(expense.getOrgId())
                .scope(expense.getScope())
                .updatedBy(expense.getUpdatedBy())
                .docStatus(expense.getDocStatus())
                .paymentStatus(expense.getPaymentStatus())
                .createdAt(expense.getCreatedAt())
                .updatedAt(expense.getUpdatedAt())
                .supplierName(expense.getSupplierName())
                .supplierGstin(expense.getSupplierGstin())
                .billNo(expense.getBillNo())
                .hsnSac(expense.getHsnSac())
                .gstRate(expense.getGstRate())
                .taxAmount(expense.getTaxAmount())
                .taxableAmount(expense.getAmount() != null
                        ? expense.getAmount().subtract(expense.getTaxAmount() != null ? expense.getTaxAmount() : BigDecimal.ZERO)
                        : null)
                .build();
    }

    public ExpenseResponse toExpenseResponse(Expense expense, String categoryName, String updatedByName) {
        ExpenseResponse response = toExpenseResponse(expense, categoryName);
        if (updatedByName != null) {
            return response.toBuilder()
                    .updatedBy(updatedByName)
                    .build();
        }
        return response;
    }
}
