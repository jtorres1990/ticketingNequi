package com.nequi.paymentmock.web;

import com.nequi.paymentmock.application.ControlService;
import com.nequi.paymentmock.domain.OutcomeRule;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Control API (non-production only, ADR-030, ADR-037): API-103 to API-110. */
@RestController
public class ControlController {

    private final ControlService control;

    public ControlController(ControlService control) {
        this.control = control;
    }

    /** API-103. */
    @GetMapping(path = "/control/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<ControlJson.RuleResponse>> listRules() {
        return Mono.fromSupplier(() -> control.listRules().stream().map(ControlJson.RuleResponse::of).toList());
    }

    /** API-104. */
    @PostMapping(path = "/control/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ControlJson.RuleResponse>> createRule(@RequestHeader HttpHeaders headers,
            @RequestBody(required = false) String body) {
        return Mono.fromSupplier(() -> {
            JsonRequests.requireJsonContentType(headers);
            ControlJson.RuleInput input = ControlJson.parseRule(body);
            OutcomeRule rule = control.createRule(input.field(), input.value(), input.behaviour());
            return ResponseEntity.status(HttpStatus.CREATED).body(ControlJson.RuleResponse.of(rule));
        });
    }

    /** API-105. */
    @DeleteMapping("/control/rules")
    public Mono<ResponseEntity<Void>> deleteAllRules() {
        return Mono.fromSupplier(() -> {
            control.deleteAllRules();
            return ResponseEntity.noContent().build();
        });
    }

    /** API-106: idempotent, 204 whether or not the rule exists. */
    @DeleteMapping("/control/rules/{ruleId}")
    public Mono<ResponseEntity<Void>> deleteRule(@PathVariable String ruleId) {
        return Mono.fromSupplier(() -> {
            control.deleteRule(ruleId);
            return ResponseEntity.noContent().build();
        });
    }

    /** API-107: replaces the defaults; an absent declinePercentage means 0 (PM-IV-008). */
    @PutMapping(path = "/control/defaults", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ControlJson.DefaultsResponse> setDefaults(@RequestHeader HttpHeaders headers,
            @RequestBody(required = false) String body) {
        return Mono.fromSupplier(() -> {
            JsonRequests.requireJsonContentType(headers);
            return ControlJson.DefaultsResponse.of(control.setDefaults(ControlJson.parseDefaults(body)));
        });
    }

    /** API-108: read-only; 404 without body when no authorization was received (PM-IV-005). */
    @GetMapping(path = "/control/authorizations/{paymentAttemptId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentJson.AuthorizationRecordResponse>> authorization(
            @PathVariable String paymentAttemptId) {
        return Mono.fromSupplier(() -> control.authorization(PaymentJson.pathAttemptId(paymentAttemptId))
                .map(view -> ResponseEntity.ok(PaymentJson.AuthorizationRecordResponse.of(view)))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /** API-109: read-only; 404 without body when no cancellation was received (PM-IV-005). */
    @GetMapping(path = "/control/cancellations/{paymentAttemptId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentJson.CancellationRecordResponse>> cancellation(
            @PathVariable String paymentAttemptId) {
        return Mono.fromSupplier(() -> control.cancellation(PaymentJson.pathAttemptId(paymentAttemptId))
                .map(view -> ResponseEntity.ok(PaymentJson.CancellationRecordResponse.of(view)))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /** API-110. */
    @PostMapping("/control/reset")
    public Mono<ResponseEntity<Void>> reset() {
        return Mono.fromSupplier(() -> {
            control.reset();
            return ResponseEntity.noContent().build();
        });
    }
}
