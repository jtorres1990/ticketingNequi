package com.nequi.ticketing.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * SPK-021 (architecture v2 §13 #20, ADR-030): the reactive HTTP client of Spring Boot 4.x (Spring Framework 7
 * {@code WebClient} on Reactor Netty) against a reactive test server with simulated latency and failures:
 * effective response and per-call timeouts, connect timeout configuration, connection failures, status
 * classification of 2xx / 4xx / 5xx without exceptions, premature close, and no blocking call (BlockHound is
 * installed in this JVM). The results are recorded in the INC-007 report.
 */
class PaymentHttpClientSpikeTest {

    private static DisposableServer server;

    @BeforeAll
    static void startServer() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .get("/delay/{millis}", (request, response) -> Mono
                                .delay(Duration.ofMillis(Long.parseLong(request.param("millis"))))
                                .then(response.status(200).sendString(Mono.just("late")).then()))
                        .get("/status/{code}", (request, response) -> response
                                .status(Integer.parseInt(request.param("code")))
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"code\":\"S" + request.param("code") + "\"}"))
                                .then())
                        .get("/close", (request, response) -> {
                            response.withConnection(Connection::dispose);
                            return Mono.never();
                        }))
                .bindNow();
    }

    @AfterAll
    static void stopServer() {
        server.disposeNow();
    }

    @Test
    @DisplayName("SPK-021 the Reactor Netty response timeout ends a slow response without blocking")
    void responseTimeout() {
        WebClient client = client(Duration.ofMillis(300), Duration.ofMillis(300));
        AtomicReference<String> thread = new AtomicReference<>();
        long start = System.nanoTime();

        StepVerifier.create(client.get().uri("/delay/2000").retrieve().bodyToMono(String.class)
                        .doOnError(error -> thread.set(Thread.currentThread().getName())))
                .expectErrorSatisfies(error -> {
                    record("response timeout", error, start);
                    assertThat(error).isInstanceOf(WebClientRequestException.class);
                    assertThat(causes(error)).contains("io.netty.handler.timeout.ReadTimeoutException");
                })
                .verify(Duration.ofSeconds(5));

        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsed).isBetween(250L, 1_500L);
        // Any Reactor Netty event loop: NIO on Windows and macOS, epoll on Linux.
        assertThat(thread.get()).startsWith("reactor-http-");
    }

    @Test
    @DisplayName("SPK-021 a Reactor timeout per call cancels the exchange at its deadline")
    void reactorTimeout() {
        WebClient client = client(Duration.ofSeconds(5), Duration.ofSeconds(5));
        long start = System.nanoTime();

        StepVerifier.create(client.get().uri("/delay/2000").retrieve().bodyToMono(String.class)
                        .timeout(Duration.ofMillis(300), Schedulers.parallel()))
                .expectErrorSatisfies(error -> {
                    record("reactor timeout", error, start);
                    assertThat(error).isInstanceOf(TimeoutException.class);
                })
                .verify(Duration.ofSeconds(5));

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(250L, 1_500L);
    }

    @Test
    @DisplayName("SPK-021 the connect timeout is applied as a channel option and a refused connection is a request error")
    void connectionFailures() {
        HttpClient http = netty(Duration.ofMillis(700), Duration.ofSeconds(1));
        Map<ChannelOption<?>, ?> options = http.configuration().options();
        assertThat(options.get(ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(700);

        DisposableServer closed = HttpServer.create().host("127.0.0.1").port(0).bindNow();
        int port = closed.port();
        closed.disposeNow();
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl("http://127.0.0.1:" + port)
                .build();
        long start = System.nanoTime();

        StepVerifier.create(client.get().uri("/").retrieve().bodyToMono(String.class))
                .expectErrorSatisfies(error -> {
                    record("connection refused", error, start);
                    assertThat(error).isInstanceOf(WebClientRequestException.class);
                    assertThat(causes(error)).anyMatch(name -> name.contains("ConnectException")
                            || name.contains("ConnectTimeoutException"));
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("SPK-021 2xx, 4xx and 5xx are exposed as status codes with their bodies by exchangeToMono")
    void statusClassification() {
        WebClient client = client(Duration.ofSeconds(1), Duration.ofSeconds(1));
        List<String> observed = new ArrayList<>();

        StepVerifier.create(Flux.just(200, 400, 401, 422, 500, 503)
                        .concatMap(code -> client.get().uri("/status/{code}", code)
                                .exchangeToMono(response -> response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .map(body -> response.statusCode().value() + " "
                                                + response.statusCode().is4xxClientError() + " "
                                                + response.statusCode().is5xxServerError() + " " + body)))
                        .doOnNext(observed::add)
                        .then())
                .verifyComplete();

        System.out.println("SPK-021 statuses: " + observed);
        assertThat(observed).containsExactly(
                "200 false false {\"code\":\"S200\"}",
                "400 true false {\"code\":\"S400\"}",
                "401 true false {\"code\":\"S401\"}",
                "422 true false {\"code\":\"S422\"}",
                "500 false true {\"code\":\"S500\"}",
                "503 false true {\"code\":\"S503\"}");
    }

    @Test
    @DisplayName("SPK-021 a connection closed without response is a request error")
    void prematureClose() {
        WebClient client = client(Duration.ofSeconds(1), Duration.ofSeconds(1));
        long start = System.nanoTime();

        StepVerifier.create(client.get().uri("/close").retrieve().bodyToMono(String.class))
                .expectErrorSatisfies(error -> {
                    record("premature close", error, start);
                    assertThat(error).isInstanceOf(WebClientRequestException.class);
                    assertThat(causes(error)).anyMatch(name -> name.contains("PrematureCloseException"));
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("SPK-021 NFR-003 200 concurrent calls from parallel threads complete on the event loop without blocking")
    void concurrentCallsDoNotBlock() {
        WebClient client = client(Duration.ofSeconds(2), Duration.ofSeconds(2));
        long start = System.nanoTime();

        StepVerifier.create(Flux.range(0, 200)
                        .flatMap(index -> Mono.defer(() -> client.get().uri("/delay/50").retrieve()
                                        .bodyToMono(String.class))
                                .subscribeOn(Schedulers.parallel())
                                .map(body -> Schedulers.isInNonBlockingThread()), 64)
                        .collectList())
                .assertNext(nonBlocking -> {
                    assertThat(nonBlocking).hasSize(200).containsOnly(true);
                })
                .verifyComplete();
        System.out.println("SPK-021 200 concurrent calls in "
                + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms");
    }

    private static WebClient client(Duration connectTimeout, Duration responseTimeout) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(netty(connectTimeout, responseTimeout)))
                .baseUrl("http://127.0.0.1:" + server.port())
                .build();
    }

    private static HttpClient netty(Duration connectTimeout, Duration responseTimeout) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
    }

    private static List<String> causes(Throwable error) {
        List<String> names = new ArrayList<>();
        for (Throwable current = error; current != null && names.size() < 10; current = current.getCause()) {
            names.add(current.getClass().getName());
        }
        return names;
    }

    private static void record(String scenario, Throwable error, long start) {
        System.out.println("SPK-021 " + scenario + ": " + causes(error) + " after "
                + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms");
    }
}
