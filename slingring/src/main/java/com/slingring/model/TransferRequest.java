package com.slingring.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * TransferRequest   payload sent to POST /peer/request
 * no file included
 * 
 * @param transferId  
 * @param senderName
 * @param fileName
 * @param size
 * @param sha256      64 chars checksum
 */
public record TransferRequest(String transferId,
                              String senderName,
                              String fileName,
                              long size,
                              String sha256) {
 
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_NAME_LENGTH = 255;
 
    public TransferRequest {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
 
        if (transferId.isBlank()) {
            throw new IllegalArgumentException("transferId is blank");
        }
        if (senderName.isBlank() || senderName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid sender name");
        }
        if (fileName.isBlank() || fileName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid file name");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Negative size");
        }
        sha256 = sha256.toLowerCase();
        if (!SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
    }
}
 
