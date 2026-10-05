package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.WebTestSupport;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * API-111 and the {@code X-Api-Key} scheme (ADR-032, PM-IV-004, PM-IV-005). The validator does not evaluate the
 * {@code apiKey} scheme (PM-SPK-003), so every protected operation is asserted explicitly here.
 */
class HealthAndSecurityWebTest extends WebTestSupport {

    static final String AUTH_BODY = json("{'paymentAttemptId':'sec-1','orderId':'8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11',"
            + "'eventId':'8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c12','customerRef':'c','ticketIds':['T1']}");

    /** Every protected operation of the contract, API-101 to API-110, with a contract-valid request. */
    static Stream<Arguments> protectedOperations() {
        return Stream.of(
                Arguments.of("API-101", HttpMethod.POST, "/payments", AUTH_BODY),
                Arguments.of("API-102", HttpMethod.POST, "/payments/sec-1/cancellation", null),
                Arguments.of("API-103", HttpMethod.GET, "/control/rules", null),
                Arguments.of("API-104", HttpMethod.POST, "/control/rules",
                        json("{'match':{'orderId':'x'},'behaviour':{'type':'APPROVE'}}")),
                Arguments.of("API-105", HttpMethod.DELETE, "/control/rules", null),
                Arguments.of("API-106", HttpMethod.DELETE, "/control/rules/rule-1", null),
                Arguments.of("API-107", HttpMethod.PUT, "/control/defaults", json("{'defaultOutcome':'APPROVED'}")),
                Arguments.of("API-108", HttpMethod.GET, "/control/authorizations/sec-1", null),
                Arguments.of("API-109", HttpMethod.GET, "/control/cancellations/sec-1", null),
                Arguments.of("API-110", HttpMethod.POST, "/control/reset", null));
    }

    static List<Consumer<HttpHeaders>> invalidCredentials() {
        return List.of(
                headers -> { },
                headers -> headers.set("X-Api-Key", ""),
                headers -> headers.set("X-Api-Key", "wrong-key"),
                headers -> headers.set("X-Api-Key", API_KEY + "x"),
                headers -> headers.set("X-Api-Key", API_KEY.substring(1)),
                headers -> headers.set("X-Api-Key", API_KEY.toUpperCase()),
                headers -> headers.addAll("X-Api-Key", List.of(API_KEY, API_KEY)),
                headers -> headers.set("Authorization", "Bearer " + API_KEY));
    }

    @Test
    void healthAnswers200WithoutBodyAndWithoutApiKey() {
        Response response = send(HttpMethod.GET, "/health", null, headers -> { }).assertConforms();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void healthIgnoresAnyPresentedKey() {
        assertThat(send(HttpMethod.GET, "/health", null, headers -> headers.set("X-Api-Key", "wrong")).assertConforms()
                .status()).isEqualTo(200);
        assertThat(send(HttpMethod.GET, "/health", null, headers -> headers.set("X-Api-Key", API_KEY)).assertConforms()
                .status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("protectedOperations")
    void everyProtectedOperationRejectsMissingOrInvalidKeysWith401(String id, HttpMethod method, String path,
            String body) {
        for (Consumer<HttpHeaders> credentials : invalidCredentials()) {
            Response response = send(method, path, body, headers -> {
                credentials.accept(headers);
                if (id.equals("API-101")) {
                    headers.set("Idempotency-Key", "sec-1");
                }
            }).assertConforms();
            assertThat(response.status()).as(id).isEqualTo(401);
            assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.json().get("code").stringValue()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.body()).doesNotContain(API_KEY).doesNotContain("wrong-key");
        }
    }

    @Test
    void authenticationIsEvaluatedBeforeRoutingAlsoOnUnknownPaths() {
        Response withoutKey = send(HttpMethod.GET, "/does-not-exist", null, headers -> { });
        assertThat(withoutKey.status()).isEqualTo(401);
        assertThat(withoutKey.json().get("code").stringValue()).isEqualTo("UNAUTHENTICATED");

        Response withKey = send(HttpMethod.GET, "/does-not-exist", null, headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(withKey.status()).isEqualTo(404);
        assertThat(withKey.json().get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void noManagementEndpointsAreExposed() {
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/env", "/error")) {
            Response response = send(HttpMethod.GET, path, null, headers -> headers.set("X-Api-Key", API_KEY));
            assertThat(response.status()).as(path).isEqualTo(404);
        }
    }

    @Test
    void corsIsNotEnabled() {
        Response preflight = send(HttpMethod.OPTIONS, "/control/rules", null, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.setOrigin("http://evil.example");
            headers.setAccessControlRequestMethod(HttpMethod.GET);
        });
        assertThat(preflight.headers().getAccessControlAllowOrigin()).isNull();
    }
}
