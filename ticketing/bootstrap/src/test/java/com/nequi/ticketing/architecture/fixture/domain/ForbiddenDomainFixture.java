package com.nequi.ticketing.architecture.fixture.domain;

import org.springframework.util.StringUtils;

public final class ForbiddenDomainFixture {

    private ForbiddenDomainFixture() {
    }

    public static boolean usesFrameworkType(String value) {
        return StringUtils.hasText(value);
    }
}

