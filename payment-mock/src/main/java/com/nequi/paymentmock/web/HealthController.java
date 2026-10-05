package com.nequi.paymentmock.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** API-111: liveness without authentication; 200 without body (PM-IV-004). */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Mono<ResponseEntity<Void>> health() {
        return Mono.just(ResponseEntity.ok().build());
    }
}
