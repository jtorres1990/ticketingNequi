package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.WebApiFlowTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiTestServer.Reply;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CMP-017 request guard (NFR-010, EVAL-008, ADR-032, IV-005): request bodies above 256 KB are rejected with
 * 413 without being processed, and API-004 is limited per authenticated subject to 10 requests per 10 s with an
 * immediate 429 carrying {@code Retry-After}. The limiter runs on a controlled clock.
 */
class RequestGuardTest {

    private static final int LIMIT = WebApiSettings.DEPLOYED.maximumBodyBytes();

    private static TestTokenIssuer issuer;

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private WebApiMocks mocks;
    private WebApiTestServer server;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(WebApiTestServer.NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void startServer() {
        mocks = new WebApiMocks();
        when(mocks.startPurchase.startPurchase(any()))
                .thenAnswer(invocation -> Mono.just(new PurchaseResult(WebApiMocks.order(WebApiMocks.ORDER_ID), true)));
        server = WebApiTestServer.start(issuer, mocks.useCases(), WebApiSettings.DEPLOYED,
                settings -> new SubjectRateLimiter(settings, nanos::get));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("NFR-010 ADR-032 declared body above 256 KB: 413 PAYLOAD_TOO_LARGE before authentication and without processing")
    void declaredBodyTooLarge() {
        byte[] body = new byte[LIMIT + 1];

        Reply anonymous = server.send(server.client.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body), false);
        Reply authenticated = server.send(server.client.post().uri("/api/v1/orders")
                .headers(headers -> {
                    headers.setBearerAuth(server.token(TestTokenIssuer.CUSTOMER_A));
                    headers.set("Idempotency-Key", WebApiMocks.key(1));
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body), false);

        assertProblem(anonymous, 413, "PAYLOAD_TOO_LARGE");
        assertProblem(authenticated, 413, "PAYLOAD_TOO_LARGE");
        verifyNoInteractions(mocks.createEvent, mocks.startPurchase);
    }

    @Test
    @DisplayName("NFR-010 ADR-032 chunked body above 256 KB: 413 while reading; exactly 256 KB is read and validated")
    void chunkedBodyTooLarge() {
        Flux<DataBuffer> oversized = Flux.range(0, LIMIT / 8_192 + 1)
                .map(index -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[8_192]));
        Reply tooLarge = server.send(server.client.post().uri("/api/v1/events")
                .headers(headers -> {
                    headers.setBearerAuth(server.token(TestTokenIssuer.ADMIN));
                    headers.set("Idempotency-Key", WebApiMocks.key(1));
                })
                .contentType(MediaType.APPLICATION_JSON).body(oversized, DataBuffer.class), false);
        when(mocks.createEvent.createEvent(any()))
                .thenReturn(Mono.just(new EventCreationResult(WebApiMocks.provisioning(), false)));
        byte[] exactly = (" ".repeat(LIMIT - WebApiMocks.EVENT_BODY.length()) + WebApiMocks.EVENT_BODY)
                .getBytes(StandardCharsets.UTF_8);
        Reply atTheLimit = server.send(server.client.post().uri("/api/v1/events")
                .headers(headers -> {
                    headers.setBearerAuth(server.token(TestTokenIssuer.ADMIN));
                    headers.set("Idempotency-Key", WebApiMocks.key(2));
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(exactly)), DataBuffer.class), true);

        assertProblem(tooLarge, 413, "PAYLOAD_TOO_LARGE");
        assertThat(exactly).hasSize(LIMIT);
        assertThat(atTheLimit.status()).isEqualTo(202);
        verify(mocks.createEvent, times(1)).createEvent(any());
    }

    @Test
    @DisplayName("ADR-032 IV-005 API-004 per subject: 10 requests per 10 s, the 11th is 429 RATE_LIMITED with Retry-After immediately")
    void purchaseRateLimitPerSubject() {
        for (int request = 1; request <= 10; request++) {
            Reply accepted = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(request)), true);
            assertThat(accepted.status()).isEqualTo(200);
        }
        nanos.addAndGet(Duration.ofMillis(2_300).toNanos());

        Reply limited = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(11)), true);
        Reply otherSubject = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_B, WebApiMocks.key(12)), true);

        assertProblem(limited, 429, "RATE_LIMITED");
        assertThat(limited.header(HttpHeaders.RETRY_AFTER)).isEqualTo("8");
        assertThat(otherSubject.status()).isEqualTo(200);
        verify(mocks.startPurchase, times(11)).startPurchase(any());
        assertThat(server.events.rateLimited).hasSize(1);

        nanos.addAndGet(Duration.ofMillis(7_700).toNanos());
        Reply afterPeriod = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(13)), true);
        assertThat(afterPeriod.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADR-032 the limiter applies to API-004 only: Order queries of the same subject are not limited")
    void limiterOnlyOnPurchases() {
        when(mocks.getOrder.getOrder(any())).thenReturn(Mono.just(WebApiMocks.order(WebApiMocks.ORDER_ID)));
        for (int request = 1; request <= 10; request++) {
            server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(request)), true);
        }

        for (int query = 0; query < 12; query++) {
            Reply reply = server.send(server.client.get().uri("/api/v1/orders/" + WebApiMocks.ORDER_ID)
                    .headers(headers -> headers.setBearerAuth(server.token(TestTokenIssuer.CUSTOMER_A))), true);
            assertThat(reply.status()).isEqualTo(200);
        }
        assertProblem(server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(99)), true),
                429, "RATE_LIMITED");
    }

    @Test
    @DisplayName("ADR-032 IV-005 rate-limited requests reach no use case and a forbidden identity is never counted")
    void rejectedRequestsDoNotReachTheUseCase() {
        for (int request = 1; request <= 12; request++) {
            server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_B, WebApiMocks.key(request)), true);
        }
        Reply forbidden = server.send(WebApiMocks.purchase(server, TestTokenIssuer.ADMIN, WebApiMocks.key(13)), false);

        verify(mocks.startPurchase, times(10)).startPurchase(any());
        assertProblem(forbidden, 403, "FORBIDDEN");
        verify(mocks.getOrder, never()).getOrder(any());
    }

    @Test
    @DisplayName("IV-005 limiter: default 10 per 10 s, wait reported until the next permission, idle subjects are discarded after one period")
    void limiterUnit() {
        AtomicLong clock = new AtomicLong(0);
        SubjectRateLimiter limiter = new SubjectRateLimiter(WebApiSettings.DEPLOYED, clock::get);
        for (int request = 0; request < 10; request++) {
            assertThat(limiter.tryAcquire("customer")).isEmpty();
        }
        clock.addAndGet(Duration.ofSeconds(4).toNanos());

        Optional<Duration> wait = limiter.tryAcquire("customer");

        assertThat(wait).contains(Duration.ofSeconds(6));
        assertThat(limiter.trackedSubjects()).isEqualTo(1);
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        assertThat(limiter.trackedSubjects()).isZero();
        assertThat(limiter.tryAcquire("customer")).isEmpty();
        assertThat(new SubjectRateLimiter(WebApiSettings.DEPLOYED).tryAcquire("other")).isEmpty();
        assertThat(WebApiSettings.DEPLOYED.purchaseRateLimit()).isEqualTo(10);
        assertThat(WebApiSettings.DEPLOYED.purchaseRateLimitPeriod()).isEqualTo(Duration.ofSeconds(10));
        assertThat(WebApiSettings.DEPLOYED.maximumBodyBytes()).isEqualTo(256 * 1024);
    }
}
