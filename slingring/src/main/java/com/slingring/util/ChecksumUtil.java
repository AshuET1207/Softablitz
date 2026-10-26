package com.slingring.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public final class ChecksumUtil {
 
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final HexFormat HEX = HexFormat.of(); // lowercase
 
    private ChecksumUtil() { }
 
    /** Hash a whole file. */
    public static String sha256(Path file) throws IOException {
        return sha256(file, bytes -> { }, () -> false);
    }
 
    /**
     * Hash a file while reporting progress and honouring cancellation.
     *
     * @param onBytes   called after each chunk with the number of bytes just read
     * @param cancelled polled between chunks; if it returns true the hash is abandoned
     * @throws CancellationException if cancelled
     */
    public static String sha256(Path file, LongConsumer onBytes, BooleanSupplier cancelled)
            throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Hashing cancelled");
                }
                digest.update(buffer, 0, read);
                onBytes.accept(read);
            }
        }
        return toHex(digest.digest());
    }
 
    /**
     * A fresh digest, for hashing while streaming (e.g. receiver updates it with
     * each chunk it writes, then calls {@link #toHex(byte[])} on the result).
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required to exist in every Java platform implementation.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
 
    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
 
    /** Case-insensitive, constant-time comparison of two hex digests. */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(
                    HEX.parseHex(expectedHex.toLowerCase()),
                    HEX.parseHex(actualHex.toLowerCase()));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
    }
}
 