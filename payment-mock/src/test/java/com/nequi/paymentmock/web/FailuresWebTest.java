package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.WebTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** DEFINITIVE_ERROR (422) and TRANSIENT_THEN_OUTCOME (503) over HTTP (AC-021, ALT-004, ERR-008, ADR-035, PM-IV-009). */
class FailuresWebTest extends WebTestSupport {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b";

    static String body(String attemptId) {
        return json("{'paymentAttemptId':'" + attemptId + "','orderId':'" + ORDER + "','eventId':'" + EVENT
                + "','customerRef':'c-1','ticketIds':['T1']}");
    }

    Response authorize(String attemptId) {
        return send(HttpMethod.POST, "/payments", body(attemptId), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", attemptId);
        }).assertConforms();
    }

    @Test
    void definitiveErrorAnswers422OnEveryInvocation() {
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'DEFINITIVE_ERROR'}}"));
        for (int i = 1; i <= 3; i++) {
            Response response = authorize("d-1");
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.json().get("code").stringValue()).isEqualTo("SIMULATED_DEFINITIVE_ERROR");
            JsonNode record = get("/control/authorizations/d-1").assertConforms().json();
            assertThat(record.get("invocations").intValue()).isEqualTo(i);
            assertThat(record.has("result")).isFalse();
        }
        assertThat(post("/payments/d-1/cancellation", null).json().get("cancellationStatus").stringValue())
                .isEqualTo("REGISTERED_BEFORE_CHARGE");
        assertThat(authorize("d-1").json().get("reasonCode").stringValue()).isEqualTo("ATTEMPT_CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void exactlyNTransientFailuresThenTheStoredFinalResult(int failures) {
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME',"
                + "'transientFailures':" + failures + ",'finalOutcome':'DECLINED','reasonCode':'CARD_DECLINED'}}"));
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            Response response = authorize("t-1");
            statuses.add(response.status());
            assertThat(response.json().get("code").stringValue()).isEqualTo("SIMULATED_TRANSIENT_FAILURE");
        }
        assertThat(statuses).hasSize(failures).allMatch(status -> status == 503);
        JsonNode decided = authorize("t-1").json();
        assertThat(decided.get("status").stringValue()).isEqualTo("DECLINED");
        assertThat(decided.get("reasonCode").stringValue()).isEqualTo("CARD_DECLINED");
        assertThat(decided.get("replayed").booleanValue()).isFalse();
        JsonNode replay = authorize("t-1").json();
        assertThat(replay.get("replayed").booleanValue()).isTrue();
        assertThat(get("/control/authorizations/t-1").json().get("invocations").intValue()).isEqualTo(failures + 2);
    }

    @Test
    void cancellationDuringTheTransientPhaseStopsTheFailures() {
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME',"
                + "'transientFailures':10,'finalOutcome':'APPROVED'}}"));
        assertThat(authorize("t-2").status()).isEqualTo(503);
        assertThat(post("/payments/t-2/cancellation", null).json().get("cancellationStatus").stringValue())
                .isEqualTo("REGISTERED_BEFORE_CHARGE");
        Response afterCancellation = authorize("t-2");
        assertThat(afterCancellation.status()).isEqualTo(200);
        assertThat(afterCancellation.json().get("reasonCode").stringValue()).isEqualTo("ATTEMPT_CANCELLED");
    }

    @Test
    void sustainedTransientFailuresForTheCircuitBreakerAndResetClearsProgress() {
        post("/control/rules", json("{'match':{'eventId':'" + EVENT + "'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME',"
                + "'transientFailures':1000000,'finalOutcome':'APPROVED'}}"));
        for (int i = 0; i < 20; i++) {
            assertThat(authorize("cb-" + (i % 3)).status()).isEqualTo(503);
        }
        post("/control/reset", null);
        post("/control/rules", json("{'match':{'eventId':'" + EVENT + "'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME',"
                + "'transientFailures':1,'finalOutcome':'APPROVED'}}"));
        assertThat(authorize("cb-0").status()).isEqualTo(503);
        assertThat(authorize("cb-0").status()).isEqualTo(200);
    }
}
