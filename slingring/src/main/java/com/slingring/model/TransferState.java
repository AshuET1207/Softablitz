package com.slingring.model;

/**
 * Lifecycle of one transfer on one device.
 */
public enum TransferState {

    /** Sender is computing the SHA-256 before sending the request. */
    HASHING("Preparing"),

    /** Request sent or received; waiting for the receiver to accept or refuse. */
    PENDING("Waiting for response"),

    /** Bytes are flowing. */
    ACTIVE("Transferring"),
    
    /** Receiver is hashing the saved temp file. */
    VERIFYING("Verifying"),

    // ---- Terminal states ----
    INTACT("Intact", true),
    CORRUPTED("Corrupted", true),
    REFUSED("Refused", true),
    CANCELLED("Cancelled", true),
    /** Network error, timeout, disk full, etc. */
    FAILED("Failed", true);

    private final String label;
    private final boolean terminal;

    TransferState(String label) {
        this(label, false);
    }

    TransferState(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    /** Text to show in the UI. */
    public String label() {
        return label;
    }

    /** Terminal states never change again and are the ones written to history. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True if the file arrived and passed (or failed) the checksum, i.e. a verdict exists. */
    public boolean hasVerdict() {
        return this == INTACT || this == CORRUPTED;
    }
}
