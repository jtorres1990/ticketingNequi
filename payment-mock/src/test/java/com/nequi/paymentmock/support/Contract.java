package com.nequi.paymentmock.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Access to the versioned literal copy of {@code payment-mock.openapi.v1.yaml} (PM-IV-003). */
public final class Contract {

    public static final String RESOURCE = "/contracts/payment-mock.openapi.v1.yaml";
    public static final String HASH_RESOURCE = "/contracts/payment-mock.openapi.v1.yaml.sha256";

    private Contract() {
    }

    public static byte[] bytes() {
        return read(RESOURCE);
    }

    public static String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    public static String recordedSha256() {
        return new String(read(HASH_RESOURCE), StandardCharsets.UTF_8).trim();
    }

    private static byte[] read(String resource) {
        try (InputStream in = Objects.requireNonNull(Contract.class.getResourceAsStream(resource), resource)) {
            return in.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
