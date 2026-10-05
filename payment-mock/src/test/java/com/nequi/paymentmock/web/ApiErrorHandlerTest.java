package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.test.StepVerifier;

/** PM-IV-005 catalogue for errors outside the operations, including the 500 that no rule can produce. */
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    MockServerWebExchange handle(Throwable error) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/payments"));
        StepVerifier.create(handler.handle(exchange, error)).expectComplete().verify(Duration.ofSeconds(5));
        return exchange;
    }

    static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }

    @Test
    void unexpectedErrorsAnswer500WithoutDetailsAndLogOnlyTheType(CapturedOutput output) {
        MockServerWebExchange exchange = handle(new IllegalStateException("customer secret-sub-123 key abc"));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(exchange)).isEqualTo("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal error\"}");
        assertThat(output.getAll()).contains("Unexpected internal error on POST request: java.lang.IllegalStateException")
                .doesNotContain("secret-sub-123").doesNotContain("at com.nequi");
    }

    @Test
    void frameworkAndInputErrorsMapToTheCatalogue() {
        assertThat(handle(new ServerWebInputException("bad")).getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(new UnsupportedMediaTypeStatusException("x")).getResponse().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(new DataBufferLimitException("x")).getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        MockServerWebExchange notAllowed = handle(new MethodNotAllowedException(HttpMethod.PUT, List.of(HttpMethod.GET)));
        assertThat(notAllowed.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(notAllowed)).contains("METHOD_NOT_ALLOWED");
        assertThat(body(handle(new ResponseStatusException(HttpStatus.NOT_FOUND)))).contains("NOT_FOUND");
        MockServerWebExchange otherClientError = handle(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThat(otherClientError.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(otherClientError)).contains("VALIDATION_ERROR");
        MockServerWebExchange serverError = handle(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThat(serverError.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        MockServerWebExchange validation = handle(new RequestValidationException("ticketIds must be an array"));
        assertThat(body(validation)).isEqualTo("{\"code\":\"VALIDATION_ERROR\",\"message\":\"ticketIds must be an array\"}");
    }

    @Test
    void committedResponsesPropagateTheError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        exchange.getResponse().setComplete().block(Duration.ofSeconds(5));
        IllegalStateException error = new IllegalStateException("late");
        StepVerifier.create(handler.handle(exchange, error)).expectErrorMatches(e -> e == error).verify(Duration.ofSeconds(5));
    }
}
