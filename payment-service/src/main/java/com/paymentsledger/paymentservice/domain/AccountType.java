package com.paymentsledger.paymentservice.domain;

/**
 * CUSTOMER accounts must never go negative. SYSTEM accounts (currently just
 * EXTERNAL_FUNDING) are exempt -- they represent money entering or leaving
 * the ledger from the outside world, not a real balance to protect.
 */
public enum AccountType {
    CUSTOMER,
    SYSTEM
}
