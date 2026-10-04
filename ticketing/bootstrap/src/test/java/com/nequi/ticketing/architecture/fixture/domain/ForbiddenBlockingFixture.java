package com.nequi.ticketing.architecture.fixture.domain;

public final class ForbiddenBlockingFixture {

    private ForbiddenBlockingFixture() {
    }

    public static void blocks() throws InterruptedException {
        Thread.sleep(1);
    }
}

