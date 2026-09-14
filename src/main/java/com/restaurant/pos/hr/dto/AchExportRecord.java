package com.restaurant.pos.hr.dto;

import com.ancientprogramming.fixedformat4j.annotation.Align;
import com.ancientprogramming.fixedformat4j.annotation.Field;
import com.ancientprogramming.fixedformat4j.annotation.Record;

import java.math.BigDecimal;

@Record
public class AchExportRecord {

    private String transactionCode = "22"; // 22 for Checking Credit
    private String routingNumber;
    private String accountNumber;
    private BigDecimal amount;
    private String employeeName;

    @Field(offset = 1, length = 2)
    public String getTransactionCode() {
        return transactionCode;
    }

    public void setTransactionCode(String transactionCode) {
        this.transactionCode = transactionCode;
    }

    @Field(offset = 3, length = 9)
    public String getRoutingNumber() {
        return routingNumber;
    }

    public void setRoutingNumber(String routingNumber) {
        this.routingNumber = routingNumber;
    }

    @Field(offset = 12, length = 17, align = Align.LEFT, paddingChar = ' ')
    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    @Field(offset = 29, length = 10, align = Align.RIGHT, paddingChar = '0')
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @Field(offset = 39, length = 22, align = Align.LEFT, paddingChar = ' ')
    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }
}
