package com.nequi.paymentmock.archfixture;

import com.nequi.paymentmock.archfixture.forbidden.Neighbour;
import java.time.Duration;
import reactor.core.publisher.Mono;

/** Negative control for the structural rules (G5): deliberately breaks every rule. Test sources only. */
public final class ForbiddenFixture {

    private ForbiddenFixture() {
    }

    public static Object violateEverything() throws InterruptedException {
        Thread.sleep(1);
        Object monitor = new Object();
        synchronized (monitor) {
            monitor.wait(1);
        }
        return Mono.just(new Neighbour()).delayElement(Duration.ZERO).block();
    }
}
