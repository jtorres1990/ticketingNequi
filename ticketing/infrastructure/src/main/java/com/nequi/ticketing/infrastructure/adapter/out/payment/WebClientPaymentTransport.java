package com.nequi.ticketing.infrastructure.adapter.out.payment;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * {@link PaymentTransport} on the reactive HTTP client of Spring (WebClient on Reactor Netty, verified in
 * SPK-021). Every request carries the {@code X-Api-Key} of the environment (ADR-032); the authorization
 * carries {@code Idempotency-Key = paymentAttemptId} (ADR-027). The connect and response timeouts of the
 * client are the longest call timeout, a safety net below the per-call Reactor timeout of the gateway.
 */
final class WebClientPaymentTransport implements PaymentTransport {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WebClient client;

    private WebClientPaymentTransport(WebClient client) {
        this.client = client;
    }

    static WebClientPaymentTransport create(PaymentGatewaySettings settings) {
        Duration timeout = settings.longestCallTimeout();
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, timeout.toMillis()))
                .responseTimeout(timeout);
        String baseUrl = settings.baseUrl().toString();
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader(API_KEY_HEADER, settings.apiKey())
                .build();
        return new WebClientPaymentTransport(client);
    }

    @Override
    public Mono<HttpReply> authorize(String paymentAttemptId, String jsonBody) {
        return client.post()
                .uri("/payments")
                .header(IDEMPOTENCY_KEY_HEADER, paymentAttemptId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(jsonBody)
                .exchangeToMono(WebClientPaymentTransport::reply);
    }

    @Override
    public Mono<HttpReply> cancel(String paymentAttemptId) {
        return client.post()
                .uri("/payments/{paymentAttemptId}/cancellation", paymentAttemptId)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(WebClientPaymentTransport::reply);
    }

    private static Mono<HttpReply> reply(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new HttpReply(status, body));
    }
}
