package com.nequi.paymentmock.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** G4: proves that BlockHound (installed by blockhound-junit-platform) detects blocking calls on Reactor threads. */
class BlockHoundCanaryTest {

    @Test
    void blockingCallOnAParallelThreadIsDetected() {
        AtomicReference<String> thread = new AtomicReference<>();
        Mono<Long> blocking = Mono.delay(Duration.ofMillis(1), Schedulers.parallel())
                .doOnNext(tick -> {
                    thread.set(Thread.currentThread().getName());
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        StepVerifier.create(blocking)
                .expectErrorMatches(error -> error instanceof BlockingOperationError)
                .verify(Duration.ofSeconds(10));
        assertThat(thread.get()).startsWith("parallel");
    }

    @Test
    void theSameCallOutsideReactorThreadsIsAllowed() throws InterruptedException {
        Thread.sleep(1);
        assertThat(Thread.currentThread().getName()).doesNotStartWith("parallel");
    }
}
