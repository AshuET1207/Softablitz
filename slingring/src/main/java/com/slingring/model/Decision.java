package com.slingring.model;

/** The receiver's answer to a transfer request (step 6 of the flow). */
public enum Decision {
    /** The user accepted */
    ACCEPTED,
    /** The user refused */
    REFUSED,
    /** Nobody answered within 120 seconds */
    TIMEOUT
}