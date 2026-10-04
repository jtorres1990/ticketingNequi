package com.nequi.ticketing.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.ValidationException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Assertion helpers for rejections signalled by the use cases. */
final class Rejections {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Rejections() {
    }

    static RequestRejectedException rejected(Mono<?> call, DomainErrorCode expected) {
        AtomicReference<RequestRejectedException> captured = new AtomicReference<>();
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(RequestRejectedException.class);
                    RequestRejectedException rejection = (RequestRejectedException) error;
                    assertThat(rejection.code()).isEqualTo(expected);
                    captured.set(rejection);
                })
                .verify(TIMEOUT);
        return captured.get();
    }

    static void invalid(Mono<?> call) {
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ValidationException.class);
                    assertThat(((ValidationException) error).code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
                })
                .verify(TIMEOUT);
    }

    static <T> T value(Mono<T> call) {
        AtomicReference<T> captured = new AtomicReference<>();
        StepVerifier.create(call).consumeNextWith(captured::set).expectComplete().verify(TIMEOUT);
        return captured.get();
    }
}
