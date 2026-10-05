package com.nequi.paymentmock.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable, platform-independent functions of {@code paymentAttemptId} (never {@code String.hashCode()}):
 * <ul>
 *   <li>decline bucket (PM-IV-008): first 8 bytes of SHA-256 of the UTF-8 identifier as an unsigned big-endian
 *       integer, modulo 100;</li>
 *   <li>provider reference (PM-IV-012): {@code "pm-"} followed by the first 24 hexadecimal characters of the same
 *       SHA-256.</li>
 * </ul>
 */
public final class StableHash {

    private StableHash() {
    }

    public static int declineBucket(String paymentAttemptId) {
        byte[] digest = sha256(paymentAttemptId);
        long high = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            high = (high << 8) | (digest[i] & 0xFF);
        }
        return (int) Long.remainderUnsigned(high, 100);
    }

    public static String providerReference(String paymentAttemptId) {
        return "pm-" + HexFormat.of().formatHex(sha256(paymentAttemptId)).substring(0, 24);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
