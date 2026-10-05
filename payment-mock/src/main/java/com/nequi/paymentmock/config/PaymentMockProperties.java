package com.nequi.paymentmock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration (PM-IV-004). The API key comes from the {@code PAYMENT_MOCK_API_KEY} environment
 * variable; it is mandatory and the process does not start without it. Its value is never logged.
 *
 * @param apiKey shared API key expected in {@code X-Api-Key}
 */
@ConfigurationProperties("payment-mock")
public record PaymentMockProperties(String apiKey) {

    public PaymentMockProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MOCK_API_KEY must be set to a non-blank value");
        }
    }

    @Override
    public String toString() {
        return "PaymentMockProperties[apiKey=<redacted>]";
    }
}
