package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.WebApiFlowTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.EventSummaryPage;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer.Identity;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiTestServer.Reply;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * CMP-002 over HTTP (FR-018, FR-019, TC-016, NFR-010, ADR-032, ADR-033): the Resource Server rejects every
 * invalid access token with 401 and every valid token without the authority with 403, as Problem Details that
 * reveal nothing about the token, before any use case is invoked.
 */
class ApiSecurityTest {

    private static final java.time.Instant NOW = WebApiTestServer.NOW;

    private static TestTokenIssuer issuer;

    private WebApiMocks mocks;
    private WebApiTestServer server;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void startServer() {
        mocks = new WebApiMocks();
        when(mocks.listEvents.listEvents(any())).thenReturn(Mono.just(new EventSummaryPage(List.of(), null)));
        server = WebApiTestServer.start(issuer, mocks.useCases());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("FR-018 TC-016 ADR-032 every operation without a token: 401 UNAUTHENTICATED with WWW-Authenticate Bearer, no use case invoked")
    void missingToken() {
        for (WebTestClient.RequestHeadersSpec<?> request : operations(headers -> { })) {
            Reply reply = server.send(request, false);

            assertProblem(reply, 401, "UNAUTHENTICATED");
            assertThat(reply.header(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        }
        verifyNoUseCaseCalls();
    }

    @Test
    @DisplayName("FR-018 ADR-032 SPK-022 invalid access tokens (issuer, expiry, type, client, subject, algorithm, signature): 401 without detail")
    void invalidTokens() {
        Map<String, Object> wrongIssuer = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        wrongIssuer.put("iss", "https://other-issuer.test/pool");
        Map<String, Object> expired = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        expired.put("exp", NOW.minusSeconds(61));
        Map<String, Object> notYetValid = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        notYetValid.put("nbf", NOW.plusSeconds(61));
        Map<String, Object> idToken = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        idToken.put("token_use", "id");
        Map<String, Object> otherClient = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        otherClient.put("client_id", TestTokenIssuer.OTHER_CLIENT_ID);
        Map<String, Object> noClient = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        noClient.remove("client_id");
        Map<String, Object> noExpiry = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        noExpiry.remove("exp");
        Map<String, Object> noSubject = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        noSubject.remove("sub");
        Map<String, Object> blankSubject = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        blankSubject.put("sub", " ");
        List<String> tokens = List.of(
                issuer.token(wrongIssuer),
                issuer.token(expired),
                issuer.token(notYetValid),
                issuer.token(idToken),
                issuer.token(otherClient),
                issuer.token(noClient),
                issuer.token(noExpiry),
                issuer.token(noSubject),
                issuer.token(blankSubject),
                issuer.hs256Token(TestTokenIssuer.CUSTOMER_A),
                issuer.tokenSignedByForeignKey(TestTokenIssuer.CUSTOMER_A),
                issuer.unsignedToken(TestTokenIssuer.CUSTOMER_A),
                "not.a.jwt");

        for (String token : tokens) {
            Reply reply = server.send(server.client.get().uri("/api/v1/events")
                    .headers(headers -> headers.setBearerAuth(token)), false);

            assertProblem(reply, 401, "UNAUTHENTICATED");
            assertThat(reply.header(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
            assertThat(reply.body()).doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("expired")
                    .doesNotContainIgnoringCase("signature").doesNotContainIgnoringCase("issuer")
                    .doesNotContain(token);
        }
        Reply basic = server.send(server.client.get().uri("/api/v1/events")
                .headers(headers -> headers.setBasicAuth("admin", "admin")), false);
        assertProblem(basic, 401, "UNAUTHENTICATED");
        Reply queryToken = server.send(server.client.get()
                .uri("/api/v1/events?access_token=" + issuer.token(TestTokenIssuer.CUSTOMER_A)), false);
        assertProblem(queryToken, 401, "UNAUTHENTICATED");
        verifyNoUseCaseCalls();
    }

    @Test
    @DisplayName("ADR-032 expiry is checked with 60 s of tolerance; a valid token is accepted without session")
    void expiryTolerance() {
        Map<String, Object> withinTolerance = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        withinTolerance.put("exp", NOW.minusSeconds(59));

        Reply reply = server.send(server.client.get().uri("/api/v1/events")
                .headers(headers -> headers.setBearerAuth(issuer.token(withinTolerance))), true);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("AC-028 ADR-033 identity without recognised groups: 403 FORBIDDEN on every protected operation")
    void noRecognisedGroups() {
        for (Identity identity : List.of(TestTokenIssuer.NO_GROUPS, new Identity("lowercase", List.of("admin", "customer")))) {
            for (WebTestClient.RequestHeadersSpec<?> request : operations(headers -> headers.setBearerAuth(issuer.token(identity)))) {
                assertProblem(server.send(request, false), 403, "FORBIDDEN");
            }
        }
        Map<String, Object> groupsAsText = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        groupsAsText.put("cognito:groups", "CUSTOMER");
        assertProblem(server.send(server.client.get().uri("/api/v1/events")
                .headers(headers -> headers.setBearerAuth(issuer.token(groupsAsText))), false), 403, "FORBIDDEN");
        verifyNoUseCaseCalls();
    }

    @Test
    @DisplayName("ADR-032 anything outside the contract is denied: 401 without token, 403 with a valid token")
    void outsideTheContract() {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD)) {
            Reply anonymous = new Reply(server.client.method(method).uri("/api/v1/admin/config")
                    .exchange().expectBody().returnResult());
            Reply authenticated = new Reply(server.client.method(method).uri("/api/v1/orders")
                    .headers(headers -> headers.setBearerAuth(issuer.token(TestTokenIssuer.ADMIN_CUSTOMER)))
                    .exchange().expectBody().returnResult());

            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(authenticated.status()).isEqualTo(403);
        }
        Reply actuator = new Reply(server.client.get().uri("/actuator/env")
                .headers(headers -> headers.setBearerAuth(issuer.token(TestTokenIssuer.ADMIN)))
                .exchange().expectBody().returnResult());
        assertProblem(actuator, 403, "FORBIDDEN");
        verifyNoUseCaseCalls();
    }

    private List<WebTestClient.RequestHeadersSpec<?>> operations(Consumer<HttpHeaders> auth) {
        Consumer<HttpHeaders> withKey = auth.andThen(headers -> headers.set("Idempotency-Key", WebApiMocks.key(1)));
        return List.of(
                server.client.post().uri("/api/v1/events").headers(withKey)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(WebApiMocks.EVENT_BODY),
                server.client.get().uri("/api/v1/events").headers(auth),
                server.client.get().uri("/api/v1/events/" + WebApiMocks.EVENT_ID + "/provisioning").headers(auth),
                server.client.get().uri("/api/v1/events/" + WebApiMocks.EVENT_ID + "/availability").headers(auth),
                server.client.post().uri("/api/v1/orders").headers(withKey).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(WebApiFlowTest.purchaseBody(WebApiMocks.EVENT_ID, "A-1-1")),
                server.client.get().uri("/api/v1/orders/" + WebApiMocks.ORDER_ID).headers(auth));
    }

    private void verifyNoUseCaseCalls() {
        verifyNoInteractions(mocks.createEvent, mocks.provisioningStatus, mocks.availability, mocks.startPurchase,
                mocks.getOrder);
    }
}
