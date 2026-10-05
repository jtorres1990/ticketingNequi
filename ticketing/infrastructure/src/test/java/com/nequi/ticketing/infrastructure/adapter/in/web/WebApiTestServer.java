package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal composition of the HTTP adapter for the web layer tests (the full composition and the Spring Boot
 * wiring belong to INC-010): body limit filter first, then the security chain, the router and the last-resort
 * exception handler, served by Reactor Netty on a random local port (real event loop threads, with
 * BlockHound installed). The access tokens come from {@link TestTokenIssuer}; the token clock is fixed. Every
 * exchange is validated against the copy of the OpenAPI v2 contract.
 */
final class WebApiTestServer implements AutoCloseable {

    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    final RecordingEvents events = new RecordingEvents();
    final WebTestClient client;
    private final DisposableServer server;
    private final TestTokenIssuer issuer;

    private WebApiTestServer(TestTokenIssuer issuer, WebApiUseCases useCases, WebApiSettings settings,
                             Function<WebApiSettings, SubjectRateLimiter> limiter) {
        this.issuer = issuer;
        AccessTokenSettings tokens = AccessTokenSettings.deployed(
                TestTokenIssuer.ISSUER, URI.create(issuer.jwkSetUri()), Set.of(TestTokenIssuer.CLIENT_ID));
        ReactiveJwtDecoder decoder = AccessTokens.decoder(tokens, Clock.fixed(NOW, ZoneOffset.UTC));
        ApiProblems problems = new ApiProblems(settings, events);
        SecurityWebFilterChain chain = ApiSecurity.securityWebFilterChain(
                ServerHttpSecurity.http(), settings, tokens, decoder, problems);
        RouterFunction<ServerResponse> routes = ApiRoutes.routes(settings, useCases, problems, limiter.apply(settings), events);
        HttpHandler handler = WebHttpHandlerBuilder
                .webHandler(RouterFunctions.toWebHandler(routes, WebApiCodecs.strategies(settings)))
                .filter(new RequestBodyLimitFilter(settings, problems), new WebFilterChainProxy(chain))
                .exceptionHandler(new ApiExceptionHandler(problems))
                .build();
        this.server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(handler))
                .bindNow();
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + server.port())
                .responseTimeout(Duration.ofSeconds(20))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    static WebApiTestServer start(TestTokenIssuer issuer, WebApiUseCases useCases) {
        return new WebApiTestServer(issuer, useCases, WebApiSettings.DEPLOYED, SubjectRateLimiter::new);
    }

    static WebApiTestServer start(TestTokenIssuer issuer, WebApiUseCases useCases, WebApiSettings settings,
                                  Function<WebApiSettings, SubjectRateLimiter> limiter) {
        return new WebApiTestServer(issuer, useCases, settings, limiter);
    }

    String token(TestTokenIssuer.Identity identity) {
        return issuer.token(identity);
    }

    /** Exchanges the request; the response (and the request when {@code validRequest}) must satisfy OpenAPI v2. */
    Reply send(WebTestClient.RequestHeadersSpec<?> request, boolean validRequest) {
        EntityExchangeResult<byte[]> result = request.exchange().expectBody().returnResult();
        List<String> violations = OpenApiContract.violations(result, result.getResponseBody(), validRequest);
        assertThat(violations)
                .as("OpenAPI v2 violations of %s %s -> %s %s", result.getMethod(), result.getUrl(), result.getStatus(),
                        result.getResponseBody() == null ? "" : new String(result.getResponseBody(), StandardCharsets.UTF_8))
                .isEmpty();
        return new Reply(result);
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    record Reply(EntityExchangeResult<byte[]> result) {

        int status() {
            return result.getStatus().value();
        }

        String header(String name) {
            return result.getResponseHeaders().getFirst(name);
        }

        String body() {
            byte[] body = result.getResponseBody();
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            return JSON.readTree(body());
        }

        String code() {
            return json().get("code").stringValue();
        }

        String contentType() {
            return String.valueOf(result.getResponseHeaders().getContentType());
        }
    }

    /** Records the observation hook calls. */
    static final class RecordingEvents implements WebApiEvents {

        final List<Throwable> unclassified = new CopyOnWriteArrayList<>();
        final List<String> rateLimited = new CopyOnWriteArrayList<>();

        @Override
        public void unclassifiedFailure(String traceId, Throwable error) {
            unclassified.add(error);
        }

        @Override
        public void rateLimited(String traceId) {
            rateLimited.add(traceId);
        }
    }
}
