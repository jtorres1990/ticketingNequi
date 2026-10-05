package com.nequi.paymentmock.web;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves and caches every Jackson (de)serializer used by the web layer on the startup thread. On a cold cache,
 * Jackson guards serializer creation with a lock; concurrent first requests would park Reactor Netty threads
 * (detected by BlockHound, G4). After the warm-up, request threads only hit the lock-free caches.
 */
@Component
class JsonWarmUp implements SmartInitializingSingleton {

    private final JsonMapper springMapper;

    JsonWarmUp(JsonMapper springMapper) {
        this.springMapper = springMapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        JsonRequests.warmUp();
        ErrorWriter.warmUp();
        for (Object sample : samples()) {
            springMapper.writeValueAsString(sample);
            springMapper.writerFor(sample.getClass()).writeValueAsString(sample);
        }
        springMapper.writerFor(new TypeReference<List<ControlJson.RuleResponse>>() { })
                .writeValueAsString(List.of());
    }

    static List<Object> samples() {
        return List.of(
                new ErrorBody(ErrorBody.INTERNAL_ERROR, "warm-up"),
                List.of(new ControlJson.RuleResponse("rule-0", Map.of("orderId", "o"),
                        new ControlJson.BehaviourResponse("APPROVE", null, null, null, null))),
                new ControlJson.DefaultsResponse("APPROVED", 0),
                PaymentJson.ResultResponse.of("warm-up", com.nequi.paymentmock.domain.AuthorizationResult.APPROVED,
                        false, false),
                PaymentJson.AuthorizationRecordResponse.of(new com.nequi.paymentmock.application.AuthorizationView(
                        "warm-up", 1, com.nequi.paymentmock.domain.AuthorizationResult.APPROVED, false)),
                new PaymentJson.CancellationResponse("warm-up", "REVERSED", false),
                new PaymentJson.CancellationRecordResponse("warm-up", 1, "REVERSED", "2026-01-01T00:00:00Z"));
    }
}
