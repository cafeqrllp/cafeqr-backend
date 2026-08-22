package com.restaurant.pos.credit.domain;

public enum CreditCustomerStatus {
    ACTIVE,
    SUSPENDED,
    BLOCKED,
    INACTIVE;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isSuspended() {
        return this == SUSPENDED;
    }
}
