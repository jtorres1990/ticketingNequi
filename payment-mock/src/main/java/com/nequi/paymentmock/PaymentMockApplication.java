package com.nequi.paymentmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Payment Mock (CMP-019): independent, deterministic and idempotent payment provider simulator used only in
 * non-production environments (ADR-030, ADR-037). Contract: {@code payment-mock.openapi.v1.yaml}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentMockApplication.class, args);
    }
}
