package com.nequi.ticketing.infrastructure.adapter.out.system;

import com.nequi.ticketing.application.port.out.IdGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

/**
 * Id generator port (CMP-021, ADR-032: random UUID identifiers, not guessable): version 4 UUIDs from a dedicated
 * {@link SecureRandom} instance seeded when it is created (composition time), so that generating an identifier on
 * a request thread does not read the operating system entropy source (NFR-003).
 */
public final class RandomUuidGenerator implements IdGenerator {

    private final SecureRandom random;

    public RandomUuidGenerator() {
        this(defaultRandom());
    }

    RandomUuidGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
        this.random.nextLong();
    }

    @Override
    public String newOrderId() {
        return next();
    }

    @Override
    public String newEventId() {
        return next();
    }

    private String next() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (bytes[index] & 0xff);
            least = (least << 8) | (bytes[index + 8] & 0xff);
        }
        return new UUID(most, least).toString();
    }

    static SecureRandom defaultRandom() {
        try {
            return SecureRandom.getInstance("DRBG");
        } catch (NoSuchAlgorithmException unavailable) {
            return new SecureRandom();
        }
    }
}
