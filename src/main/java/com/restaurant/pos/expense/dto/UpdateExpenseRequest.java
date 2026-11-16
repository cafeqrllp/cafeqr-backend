package com.restaurant.pos.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for updating an existing expense transaction")
public class UpdateExpenseRequest implements ExpenseBaseRequest {

    @NotNull(message = "Category ID is required")
    @Schema(description = "ID of the associated expense category")
    private UUID categoryId;

    @Schema(description = "Date and time of the expense occurrence")
    private Instant expenseDate;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    @Schema(description = "Transaction amount", example = "1500.50")
    private BigDecimal amount;

    @Schema(description = "Optional narrative description", example = "Monthly internet bill updated")
    private String description;

    @Pattern(regexp = "CASH|ONLINE|UPI|CARD|BANK|CHEQUE|MIXED", message = "Invalid payment method")
    @Schema(description = "Method of payment used", example = "CASH")
    private String paymentMethod;

    @Schema(description = "Target branch ID for organizational attribution")
    private UUID branchId;

    @Pattern(regexp = "GLOBAL|BRANCH", message = "Scope must be GLOBAL or BRANCH")
    @Schema(description = "Expense scope: GLOBAL for organization-level, BRANCH for a specific branch", example = "BRANCH")
    private String scope;

    @Schema(description = "Cash amount for mixed payment", example = "500.00")
    private BigDecimal cashAmount;

    @Schema(description = "Online amount for mixed payment", example = "1000.00")
    private BigDecimal onlineAmount;

    @Schema(description = "Supplier / vendor name on the bill", example = "Kerala State Electricity Board")
    private String supplierName;

    @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2}[A-Za-z0-9]{13}$", message = "Supplier GSTIN must be 15 characters starting with a 2-digit state code")
    @Schema(description = "Supplier GSTIN (15 characters)", example = "32AAACK1234F1Z5")
    private String supplierGstin;

    @Schema(description = "Supplier bill / invoice number", example = "EB-2026-1102")
    private String billNo;

    @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{4,8}$", message = "HSN/SAC must be 4-8 digits")
    @Schema(description = "HSN (goods) or SAC (services) code", example = "996911")
    private String hsnSac;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "GST rate cannot be negative")
    @jakarta.validation.constraints.DecimalMax(value = "100", message = "GST rate cannot exceed 100")
    @Schema(description = "GST rate in percent", example = "18")
    private BigDecimal gstRate;

    @jakarta.validation.constraints.PositiveOrZero(message = "Tax amount cannot be negative")
    @Schema(description = "GST included in amount; computed from rate when omitted", example = "228.81")
    private BigDecimal taxAmount;
}
