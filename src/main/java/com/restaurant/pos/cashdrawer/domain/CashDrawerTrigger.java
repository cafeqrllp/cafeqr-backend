package com.restaurant.pos.cashdrawer.domain;

/** Why the drawer was opened. */
public enum CashDrawerTrigger {
    /** Automatic kick after a direct POS sale was paid. */
    SALE_SETTLE,
    /** Automatic kick after an existing kitchen / table order was settled. */
    ORDER_SETTLE,
    /** Manual "No Sale" open from the POS button. */
    MANUAL,
    /** Hardware test from configuration. */
    TEST
}
