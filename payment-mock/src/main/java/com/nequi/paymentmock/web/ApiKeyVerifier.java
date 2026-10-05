package com.nequi.paymentmock.web;

import com.nequi.paymentmock.config.PaymentMockProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/** Constant-time comparison of the presented {@code X-Api-Key} with the configured key (ADR-032). */
@Component
public class ApiKeyVerifier {

    private final byte[] expected;

    public ApiKeyVerifier(PaymentMockProperties properties) {
        this.expected = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
