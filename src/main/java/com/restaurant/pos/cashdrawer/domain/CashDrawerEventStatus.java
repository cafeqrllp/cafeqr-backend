package com.restaurant.pos.cashdrawer.domain;

public enum CashDrawerEventStatus {
    /** Kick command was delivered to the printer transport. */
    SENT,
    /** Kick command could not be delivered (printer offline, no printer configured, ...). */
    FAILED
}
