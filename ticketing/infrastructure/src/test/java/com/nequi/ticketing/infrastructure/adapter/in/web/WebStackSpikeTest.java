package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * SPK-020 (architecture §13 #20, web part) and SPK-023 (#26), run before writing the HTTP adapter of INC-008,
 * with the versions managed by the Spring Boot 4.1.1 BOM (Spring Framework 7.0.9, Spring Security 7.1.1,
 * Nimbus JOSE + JWT 10.9.1) and Resilience4j 2.4.0 on JDK 25, with BlockHound installed:
 * <ul>
 *   <li>reactive Resource Server with the expected issuer and the JWK set URL configured separately and own
 *       validators (ADR-032, ADR-033): asymmetric algorithm, issuer, expiry with 60 s of tolerance, access token
 *       type, allowed client;</li>
 *   <li>a security filter chain built without an application context, with own 401 / 403 handlers;</li>
 *   <li>Problem Details (RFC 9457) written by WebFlux with extension members at the top level;</li>
 *   <li>in-memory request body limit raising {@code DataBufferLimitException} beyond the limit (413);</li>
 *   <li>non-blocking per-subject rate limiter with immediate rejection and a computable wait (Retry-After).</li>
 * </ul>
 */
class WebStackSpikeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final int LIMIT = 256 * 1024;

    private static TestTokenIssuer issuer;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    private static ReactiveJwtDecoder decoder(Clock clock) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(issuer.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                timestamps,
                new JwtIssuerValidator(TestTokenIssuer.ISSUER),
                new JwtClaimValidator<String>("token_use", "access"::equals),
                new JwtClaimValidator<String>("client_id", Set.of(TestTokenIssuer.CLIENT_ID)::contains),
                new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static Mono<Jwt> decodeOnParallel(ReactiveJwtDecoder decoder, String token) {
        return Mono.defer(() -> decoder.decode(token)).subscribeOn(Schedulers.parallel());
    }

    @Test
    @DisplayName("SPK-020 Resource Server: issuer and JWK set URL configured separately, Cognito claims decoded without audience")
    void decodesAccessTokenWithSeparateIssuerAndJwkSetUri() {
        ReactiveJwtDecoder decoder = decoder(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(URI.create(issuer.jwkSetUri()).getHost()).isNotEqualTo(URI.create(TestTokenIssuer.ISSUER).getHost());

        StepVerifier.create(decodeOnParallel(decoder, issuer.token(TestTokenIssuer.ADMIN_CUSTOMER)))
                .assertNext(jwt -> {
                    assertThat(jwt.getSubject()).isEqualTo("admin-customer");
                    assertThat(jwt.getClaimAsStringList("cognito:groups")).containsExactly("ADMIN", "CUSTOMER");
                    assertThat(jwt.getClaimAsString("token_use")).isEqualTo("access");
                    assertThat(jwt.getAudience()).isNull();
                    assertThat(jwt.getHeaders()).doesNotContainKey("typ");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("SPK-020 Resource Server validators reject issuer, expiry beyond 60 s, token type, client, algorithm and signature")
    void validatorsRejectInvalidTokens() {
        ReactiveJwtDecoder decoder = decoder(Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, Object> wrongIssuer = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        wrongIssuer.put("iss", "https://other-issuer.test");
        Map<String, Object> expiredBeyondSkew = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        expiredBeyondSkew.put("exp", NOW.minusSeconds(61));
        Map<String, Object> idToken = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        idToken.put("token_use", "id");
        Map<String, Object> otherClient = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        otherClient.put("client_id", TestTokenIssuer.OTHER_CLIENT_ID);
        Map<String, Object> withoutExpiry = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        withoutExpiry.remove("exp");
        List<String> rejected = List.of(
                issuer.token(wrongIssuer),
                issuer.token(expiredBeyondSkew),
                issuer.token(idToken),
                issuer.token(otherClient),
                issuer.token(withoutExpiry),
                issuer.hs256Token(TestTokenIssuer.CUSTOMER_A),
                issuer.tokenSignedByForeignKey(TestTokenIssuer.CUSTOMER_A),
                issuer.unsignedToken(TestTokenIssuer.CUSTOMER_A),
                "not-a-jwt");

        for (String token : rejected) {
            StepVerifier.create(decodeOnParallel(decoder, token)).expectError(JwtException.class).verify();
        }
        Map<String, Object> expiredWithinSkew = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        expiredWithinSkew.put("exp", NOW.minusSeconds(59));
        StepVerifier.create(decodeOnParallel(decoder, issuer.token(expiredWithinSkew))).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("SPK-020 security chain without application context: own 401/403 handlers and authority rules")
    void securityChainWithoutApplicationContext() {
        ReactiveJwtDecoder decoder = decoder(Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityWebFilterChain chain = ServerHttpSecurity.http()
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/admin").hasAuthority("ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint((exchange, error) -> status(exchange, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, error) -> status(exchange, HttpStatus.FORBIDDEN))
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(token -> Mono.just(
                                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                                        token,
                                        token.getClaimAsStringList("cognito:groups").stream()
                                                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                                .toList())))))
                .build();
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/admin", request -> ServerResponse.ok().bodyValue("ok"))
                .build();
        WebTestClient client = WebTestClient.bindToRouterFunction(routes)
                .webFilter(new WebFilterChainProxy(chain))
                .build();

        client.get().uri("/admin").exchange().expectStatus().isUnauthorized();
        client.get().uri("/admin").headers(h -> h.setBearerAuth(issuer.token(TestTokenIssuer.CUSTOMER_A)))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/admin").headers(h -> h.setBearerAuth(issuer.token(TestTokenIssuer.ADMIN)))
                .exchange().expectStatus().isOk();
        client.get().uri("/admin").headers(h -> h.setBearerAuth(issuer.hs256Token(TestTokenIssuer.ADMIN)))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/other").headers(h -> h.setBearerAuth(issuer.token(TestTokenIssuer.ADMIN)))
                .exchange().expectStatus().isForbidden();
    }

    private static Mono<Void> status(org.springframework.web.server.ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Test
    @DisplayName("SPK-020 Problem Details: the default type is not written unless set explicitly")
    void problemDetailsOmitDefaultType() {
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/problem", request -> ServerResponse.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .bodyValue(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)))
                .build();

        WebTestClient.bindToRouterFunction(routes).build()
                .get().uri("/problem").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.type").doesNotExist();
    }

    @Test
    @DisplayName("SPK-020 Problem Details: WebFlux writes application/problem+json with extension members at the top level")
    void problemDetailsWithExtensionMembers() {
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/problem", request -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is invalid.");
                    // Spring Framework 7 omits the default type unless it is set explicitly (observed in SPK-020).
                    problem.setType(URI.create("about:blank"));
                    problem.setProperty("code", "VALIDATION_ERROR");
                    problem.setProperty("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
                    problem.setProperty("errors", List.of(Map.of("field", "ticketIds", "reason", "must not be empty")));
                    return ServerResponse.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem);
                })
                .build();

        WebTestClient.bindToRouterFunction(routes).handlerStrategies(HandlerStrategies.withDefaults()).build()
                .get().uri("/problem").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.traceId").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
                .jsonPath("$.errors[0].field").isEqualTo("ticketIds")
                .jsonPath("$.properties").doesNotExist();
    }

    @Test
    @DisplayName("SPK-020 body limit: 256 KB accepted, one more byte raises DataBufferLimitException (413), with and without Content-Length")
    void inMemoryBodyLimit() {
        AtomicReference<String> handlerThread = new AtomicReference<>();
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .POST("/body", request -> DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), LIMIT)
                        .map(buffer -> {
                            handlerThread.set(Thread.currentThread().getName());
                            int size = buffer.readableByteCount();
                            DataBufferUtils.release(buffer);
                            return size;
                        })
                        .flatMap(size -> ServerResponse.ok().bodyValue(String.valueOf(size)))
                        .onErrorResume(DataBufferLimitException.class,
                                error -> ServerResponse.status(HttpStatus.CONTENT_TOO_LARGE).build()))
                .POST("/codec", request -> request.bodyToMono(String.class)
                        .flatMap(body -> ServerResponse.ok().bodyValue(String.valueOf(body.length())))
                        .onErrorResume(DataBufferLimitException.class,
                                error -> ServerResponse.status(HttpStatus.CONTENT_TOO_LARGE).build()))
                .build();
        HandlerStrategies strategies = HandlerStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(LIMIT))
                .build();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes, strategies)))
                .bindNow();
        try {
            WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + server.port()).build();
            client.post().uri("/body").bodyValue(new byte[LIMIT]).exchange()
                    .expectStatus().isOk().expectBody(String.class).isEqualTo(String.valueOf(LIMIT));
            client.post().uri("/body").bodyValue(new byte[LIMIT + 1]).exchange()
                    .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
            Flux<org.springframework.core.io.buffer.DataBuffer> chunked = Flux.range(0, 33)
                    .map(index -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[8 * 1024]));
            client.post().uri("/body").body(chunked, org.springframework.core.io.buffer.DataBuffer.class).exchange()
                    .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
            // Any Reactor Netty event loop: NIO on Windows and macOS, epoll on Linux.
            assertThat(handlerThread.get()).startsWith("reactor-http-");
            String text = "a".repeat(LIMIT);
            client.post().uri("/codec").contentType(MediaType.TEXT_PLAIN).bodyValue(text).exchange()
                    .expectStatus().isOk().expectBody(String.class).isEqualTo(String.valueOf(text.getBytes(StandardCharsets.UTF_8).length));
            client.post().uri("/codec").contentType(MediaType.TEXT_PLAIN).bodyValue(text + "a").exchange()
                    .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("SPK-023 Resilience4j RateLimiter: immediate rejection without waiting and a computable wait for Retry-After")
    void perSubjectRateLimiterRejectsImmediately() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build();
        AtomicRateLimiter limiter = new AtomicRateLimiter("customer-a", config, nanos::get);
        List<Boolean> decisions = new ArrayList<>();
        List<String> threads = new ArrayList<>();

        StepVerifier.create(Flux.range(0, 11)
                        .publishOn(Schedulers.parallel())
                        .map(index -> {
                            threads.add(Thread.currentThread().getName());
                            return limiter.acquirePermission();
                        }))
                .recordWith(() -> decisions)
                .expectNextCount(11)
                .verifyComplete();

        assertThat(decisions.subList(0, 10)).containsOnly(true);
        assertThat(decisions.get(10)).isFalse();
        assertThat(threads).allMatch(name -> name.startsWith("parallel-"));
        nanos.addAndGet(Duration.ofMillis(2_500).toNanos());
        long nanosToWait = limiter.getDetailedMetrics().getNanosToWait();
        assertThat(nanosToWait).isEqualTo(Duration.ofMillis(7_500).toNanos());
        assertThat(limiter.acquirePermission()).isFalse();
        nanos.addAndGet(nanosToWait);
        assertThat(limiter.acquirePermission()).isTrue();
        assertThat(limiter.getMetrics().getAvailablePermissions()).isEqualTo(9);
    }
}
