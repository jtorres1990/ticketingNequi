package com.nequi.ticketing.infrastructure;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class BlockingCallDetectorTest {

    @Test
    @DisplayName("NFR-003 BlockHound detects a deliberate blocking call on a reactive worker")
    void blockHoundCanaryDetectsBlockingCall() {
        Mono<String> blockingOperation = Mono.fromCallable(() -> {
                    Thread.sleep(10);
                    return "unexpected";
                })
                .subscribeOn(Schedulers.parallel());

        StepVerifier.create(blockingOperation)
                .expectError(BlockingOperationError.class)
                .verify(Duration.ofSeconds(5));
    }
}

