package com.nequi.ticketing.architecture.fixture.infrastructure.adapter.out.payment;

import org.springframework.util.StringUtils;

/** Control fixture: a payment adapter type that depends on something other than the HTTP contract stack. */
public final class ForbiddenPaymentFixture {

    private ForbiddenPaymentFixture() {
    }

    public static boolean usesForeignType(String value) {
        return StringUtils.hasText(value);
    }
}
