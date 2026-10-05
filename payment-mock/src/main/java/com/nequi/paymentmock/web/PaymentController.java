package com.nequi.paymentmock.web;

import com.nequi.paymentmock.application.AuthorizationReply;
import com.nequi.paymentmock.application.PaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Payment operations consumed by the ticketing payment adapter (CMP-013): API-101 and API-102. */
@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** API-101: deterministic and idempotent authorization. */
    @PostMapping(path = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> authorize(@RequestHeader HttpHeaders headers,
            @RequestBody(required = false) String body) {
        return Mono.fromSupplier(() -> PaymentJson.parseAuthorization(headers, body))
                .flatMap(input -> payments.authorize(input.paymentAttemptId(), input.payload()))
                .map(PaymentController::toResponse);
    }

    /** API-102: idempotent cancellation; no request body is expected (the ticketing adapter sends none). */
    @PostMapping(path = "/payments/{paymentAttemptId}/cancellation", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PaymentJson.CancellationResponse> cancel(@PathVariable String paymentAttemptId) {
        return Mono.fromSupplier(() -> PaymentJson.pathAttemptId(paymentAttemptId))
                .flatMap(payments::cancel)
                .map(PaymentJson.CancellationResponse::of);
    }

    static ResponseEntity<Object> toResponse(AuthorizationReply reply) {
        return switch (reply) {
            case AuthorizationReply.Authorized authorized -> ResponseEntity.ok(PaymentJson.ResultResponse.of(
                    authorized.paymentAttemptId(), authorized.result(), authorized.replayed(), authorized.cancelled()));
            case AuthorizationReply.PayloadConflict ignored -> error(HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorBody.IDEMPOTENCY_KEY_REUSED, "paymentAttemptId was already used with another payload");
            case AuthorizationReply.DefinitiveError ignored -> error(HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorBody.SIMULATED_DEFINITIVE_ERROR, "Simulated definitive error");
            case AuthorizationReply.TransientFailure ignored -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorBody.SIMULATED_TRANSIENT_FAILURE, "Simulated transient failure");
        };
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(code, message));
    }
}
