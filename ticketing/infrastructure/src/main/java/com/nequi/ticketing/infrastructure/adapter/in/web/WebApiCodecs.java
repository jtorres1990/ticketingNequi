package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.util.Objects;
import org.springframework.web.reactive.function.server.HandlerStrategies;

/**
 * Codecs of the HTTP API (CMP-017): the in-memory buffering limit of every codec equals the request body limit
 * of 256 KB (ADR-032; verified in SPK-020), so that no reader aggregates more than that in memory. The request
 * bodies of API-001 and API-004 are read by the adapter with the same limit. With Spring Boot (INC-010) the
 * equivalent property is {@code spring.http.codecs.max-in-memory-size}.
 */
public final class WebApiCodecs {

    private WebApiCodecs() {
    }

    public static HandlerStrategies strategies(WebApiSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return HandlerStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(settings.maximumBodyBytes()))
                .build();
    }
}
