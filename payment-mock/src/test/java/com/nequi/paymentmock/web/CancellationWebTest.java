package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.WebTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** API-102 and API-109 over HTTP (FG-003, BR-034, ALT-009, AC-035, ADR-030 rules 2 and 3, PM-IV-005, PM-IV-011). */
class CancellationWebTest extends WebTestSupport {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b";

    static String body(String attemptId) {
        return json("{'paymentAttemptId':'" + attemptId + "','orderId':'" + ORDER + "','eventId':'" + EVENT
                + "','customerRef':'c-1','ticketIds':['T1']}");
    }

    JsonNode authorize(String attemptId) {
        return send(HttpMethod.POST, "/payments", body(attemptId), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", attemptId);
        }).assertConforms().json();
    }

    /** As the ticketing adapter sends it: no body and no Content-Type. */
    JsonNode cancel(String attemptId) {
        Response response = post("/payments/" + attemptId + "/cancellation", null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        return response.json();
    }

    static JsonNode expected(String attemptId, String status, boolean replayed) {
        return JSON.readTree(json("{'paymentAttemptId':'" + attemptId + "','cancellationStatus':'" + status
                + "','replayed':" + replayed + "}"));
    }

    @Test
    void approvedAttemptIsReversedExactlyOnce() {
        authorize("a-1");
        assertThat(cancel("a-1")).isEqualTo(expected("a-1", "REVERSED", false));
        assertThat(cancel("a-1")).isEqualTo(expected("a-1", "REVERSED", true));
        JsonNode record = get("/control/cancellations/a-1").assertConforms().json();
        assertThat(record.get("received").intValue()).isEqualTo(2);
        assertThat(record.get("cancellationStatus").stringValue()).isEqualTo("REVERSED");
        Instant first = Instant.parse(record.get("firstReceivedAt").stringValue());
        assertThat(first).isBefore(Instant.now().plusSeconds(1)).isAfter(Instant.now().minusSeconds(60));
        assertThat(record.propertyNames()).containsExactlyInAnyOrder("paymentAttemptId", "received",
                "cancellationStatus", "firstReceivedAt");
        // The repeated authorization reports the later cancellation.
        JsonNode replay = authorize("a-1");
        assertThat(replay.get("cancelled").booleanValue()).isTrue();
        assertThat(replay.get("status").stringValue()).isEqualTo("APPROVED");
        assertThat(get("/control/authorizations/a-1").assertConforms().json().get("result").get("cancelled")
                .booleanValue()).isTrue();
    }

    @Test
    void declinedAttemptIsVoided() {
        put("/control/defaults", json("{'defaultOutcome':'DECLINED'}"));
        authorize("a-1");
        assertThat(cancel("a-1")).isEqualTo(expected("a-1", "VOIDED", false));
        assertThat(authorize("a-1").get("cancelled").booleanValue()).isTrue();
    }

    @Test
    void earlyCancellationDeclinesEveryLaterAuthorization() {
        assertThat(cancel("a-1")).isEqualTo(expected("a-1", "REGISTERED_BEFORE_CHARGE", false));
        assertThat(get("/control/authorizations/a-1").status()).isEqualTo(404);
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'APPROVE'}}"));
        JsonNode first = authorize("a-1");
        assertThat(first).isEqualTo(JSON.readTree(json("{'paymentAttemptId':'a-1','status':'DECLINED',"
                + "'providerReference':'pm-2f8fe63a6224321de5d0a24c','reasonCode':'ATTEMPT_CANCELLED',"
                + "'replayed':false,'cancelled':false}")));
        JsonNode second = authorize("a-1");
        assertThat(second.get("reasonCode").stringValue()).isEqualTo("ATTEMPT_CANCELLED");
        assertThat(second.get("replayed").booleanValue()).isTrue();
        assertThat(cancel("a-1")).isEqualTo(expected("a-1", "REGISTERED_BEFORE_CHARGE", true));
        assertThat(get("/control/cancellations/a-1").json().get("received").intValue()).isEqualTo(2);
    }

    @Test
    void cancellationAcceptsAnIgnoredBodyOrContentType() {
        Response withBody = send(HttpMethod.POST, "/payments/b-1/cancellation", json("{'reason':'whatever'}"),
                headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(withBody.status()).isEqualTo(200);
        assertThat(withBody.json().get("cancellationStatus").stringValue()).isEqualTo("REGISTERED_BEFORE_CHARGE");
    }

    @Test
    void inspectionAnswers404WithoutBodyAndPathLimitsApply() {
        Response missing = get("/control/cancellations/never");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEmpty();
        authorize("never");
        assertThat(get("/control/cancellations/never").status()).isEqualTo(404);

        for (String path : List.of("/payments/" + "x".repeat(81) + "/cancellation",
                "/control/cancellations/" + "x".repeat(81))) {
            HttpMethod method = path.startsWith("/payments") ? HttpMethod.POST : HttpMethod.GET;
            Response tooLong = send(method, path, null, headers -> headers.set("X-Api-Key", API_KEY));
            assertThat(tooLong.status()).as(path).isEqualTo(400);
            assertThat(tooLong.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
            assertThat(tooLong.responseViolations()).singleElement().asString()
                    .startsWith("validation.response.status.unknown");
        }
        Response exactly80 = post("/payments/" + "x".repeat(80) + "/cancellation", null);
        assertThat(exactly80.status()).isEqualTo(200);
    }

    @Test
    void resetForgetsCancellations() {
        cancel("a-1");
        post("/control/reset", null);
        assertThat(get("/control/cancellations/a-1").status()).isEqualTo(404);
        assertThat(authorize("a-1").get("status").stringValue()).isEqualTo("APPROVED");
    }

    @Test
    void concurrentAuthorizationAndCancellationOverHttpStayConsistent() throws Exception {
        for (int iteration = 0; iteration < 25; iteration++) {
            String id = "race-" + iteration;
            List<JsonNode> auths = Collections.synchronizedList(new ArrayList<>());
            List<JsonNode> cancels = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                boolean authorization = t % 2 == 0;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        if (authorization) {
                            auths.add(authorize(id));
                        } else {
                            cancels.add(cancel(id));
                        }
                    } catch (Throwable error) {
                        errors.add(error);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(errors).isEmpty();
            String reason = auths.getFirst().has("reasonCode") ? auths.getFirst().get("reasonCode").stringValue() : "-";
            String status = cancels.getFirst().get("cancellationStatus").stringValue();
            assertThat(reason + "/" + status).isIn("-/REVERSED", "ATTEMPT_CANCELLED/REGISTERED_BEFORE_CHARGE");
            assertThat(get("/control/cancellations/" + id).json().get("received").intValue()).isEqualTo(2);
            assertThat(get("/control/authorizations/" + id).json().get("invocations").intValue()).isEqualTo(2);
        }
    }
}
