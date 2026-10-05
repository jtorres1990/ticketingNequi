package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.WebTestSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** API-101 and API-108 over HTTP (FR-015, FR-017, AC-019, AC-020, AC-023, AC-024, ALT-005, PM-IV-005, PM-IV-008 to PM-IV-012). */
class AuthorizationWebTest extends WebTestSupport {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b";
    static final String SECRET_CUSTOMER = "customer-sub-0f9e8d7c";

    static String body(String attemptId) {
        return body(attemptId, ORDER, SECRET_CUSTOMER, "['T1','T2']");
    }

    static String body(String attemptId, String orderId, String customerRef, String tickets) {
        return json("{'paymentAttemptId':'" + attemptId + "','orderId':'" + orderId + "','eventId':'" + EVENT
                + "','customerRef':'" + customerRef + "','ticketIds':" + tickets + "}");
    }

    Response authorize(String attemptId, String jsonBody) {
        return send(HttpMethod.POST, "/payments", jsonBody, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", attemptId);
        });
    }

    @Test
    void approvesByDefaultWithTheContractShape() {
        Response response = authorize("a-1", body("a-1")).assertConforms();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).isEqualTo(JSON.readTree(json("{'paymentAttemptId':'a-1','status':'APPROVED',"
                + "'providerReference':'pm-2f8fe63a6224321de5d0a24c','replayed':false,'cancelled':false}")));
        assertThat(response.body()).doesNotContain("reasonCode").doesNotContain("null").doesNotContain(SECRET_CUSTOMER);
    }

    @Test
    void declinesByRuleWithItsReasonCode() {
        post("/control/rules", json("{'match':{'customerRef':'" + SECRET_CUSTOMER + "'},'behaviour':{'type':'DECLINE',"
                + "'reasonCode':'INSUFFICIENT_FUNDS'}}"));
        Response response = authorize("a-1", body("a-1")).assertConforms();
        assertThat(response.json().get("status").stringValue()).isEqualTo("DECLINED");
        assertThat(response.json().get("reasonCode").stringValue()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.json().get("providerReference").stringValue()).isEqualTo("pm-2f8fe63a6224321de5d0a24c");
    }

    @Test
    void ruleWithoutReasonDeclinesWithRuleDeclinedAndPrecedenceAppliesOverHttp() {
        post("/control/rules", json("{'match':{'eventId':'" + EVENT + "'},'behaviour':{'type':'APPROVE'}}"));
        post("/control/rules", json("{'match':{'ticketId':'T2'},'behaviour':{'type':'DECLINE'}}"));
        Response response = authorize("a-1", body("a-1")).assertConforms();
        assertThat(response.json().get("reasonCode").stringValue()).isEqualTo("RULE_DECLINED");
        post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':{'type':'APPROVE'}}"));
        assertThat(authorize("a-2", body("a-2")).assertConforms().json().get("status").stringValue()).isEqualTo("APPROVED");
    }

    @Test
    void defaultsAndPercentageAreDeterministic() {
        put("/control/defaults", json("{'defaultOutcome':'DECLINED'}"));
        assertThat(authorize("a-1", body("a-1")).assertConforms().json().get("reasonCode").stringValue())
                .isEqualTo("RULE_DECLINED");
        // a-1 -> bucket 41, pm-vector-2 -> 39, pm-vector-1 -> 62
        put("/control/defaults", json("{'defaultOutcome':'APPROVED','declinePercentage':40}"));
        assertThat(authorize("pm-vector-2", body("pm-vector-2")).assertConforms().json().get("reasonCode").stringValue())
                .isEqualTo("PERCENTAGE_DECLINED");
        assertThat(authorize("pm-vector-1", body("pm-vector-1")).assertConforms().json().get("status").stringValue())
                .isEqualTo("APPROVED");
        assertThat(authorize("a-2", body("a-2")).assertConforms().json().has("status")).isTrue();
    }

    @Test
    void repetitionReplaysWithStableProviderReferenceAndCountsInvocations() {
        JsonNode first = authorize("a-1", body("a-1")).assertConforms().json();
        put("/control/defaults", json("{'defaultOutcome':'DECLINED','declinePercentage':100}"));
        JsonNode second = authorize("a-1", body("a-1")).assertConforms().json();
        JsonNode third = authorize("a-1", body("a-1")).assertConforms().json();
        assertThat(first.get("replayed").booleanValue()).isFalse();
        assertThat(second.get("replayed").booleanValue()).isTrue();
        assertThat(third.get("replayed").booleanValue()).isTrue();
        for (JsonNode reply : List.of(second, third)) {
            assertThat(reply.get("status")).isEqualTo(first.get("status"));
            assertThat(reply.get("providerReference")).isEqualTo(first.get("providerReference"));
            assertThat(reply.get("cancelled").booleanValue()).isFalse();
        }
        Response record = get("/control/authorizations/a-1").assertConforms();
        assertThat(record.json()).isEqualTo(JSON.readTree(json("{'paymentAttemptId':'a-1','invocations':3,'result':"
                + "{'paymentAttemptId':'a-1','status':'APPROVED','providerReference':'pm-2f8fe63a6224321de5d0a24c',"
                + "'cancelled':false}}")));
    }

    @Test
    void payloadConflictAnswers422AndKeepsTheAttempt() {
        authorize("a-1", body("a-1"));
        Response conflict = authorize("a-1", body("a-1", ORDER, "someone-else", "['T1','T2']")).assertConforms();
        assertThat(conflict.status()).isEqualTo(422);
        assertThat(conflict.json().get("code").stringValue()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(conflict.body()).doesNotContain("someone-else").doesNotContain(SECRET_CUSTOMER);
        // Same Tickets in another order: same payload, normal replay.
        assertThat(authorize("a-1", body("a-1", ORDER, SECRET_CUSTOMER, "['T2','T1']")).assertConforms()
                .json().get("replayed").booleanValue()).isTrue();
        JsonNode record = get("/control/authorizations/a-1").json();
        assertThat(record.get("invocations").intValue()).isEqualTo(3);
        assertThat(record.get("result").get("status").stringValue()).isEqualTo("APPROVED");
    }

    static Stream<String> invalidAuthorizations() {
        String ok = "{'paymentAttemptId':'v-1','orderId':'" + ORDER + "','eventId':'" + EVENT
                + "','customerRef':'c','ticketIds':['T1']}";
        return Stream.of(
                ok.replace("'orderId':'" + ORDER + "'", "'orderId':'not-a-uuid'"),
                ok.replace("'eventId':'" + EVENT + "'", "'eventId':'" + EVENT + "x'"),
                ok.replace("'orderId':'" + ORDER + "',", ""),
                ok.replace("'customerRef':'c'", "'customerRef':'" + "c".repeat(129) + "'"),
                ok.replace("'customerRef':'c'", "'customerRef':null"),
                ok.replace("'customerRef':'c'", "'customerRef':7"),
                ok.replace("['T1']", "[]"),
                ok.replace("['T1']", "['1','2','3','4','5','6','7','8','9','10','11']"),
                ok.replace("['T1']", "['" + "t".repeat(25) + "']"),
                ok.replace("['T1']", "[1]"),
                ok.replace("['T1']", "'T1'"),
                ok.replace("'customerRef':'c'", "'customerRef':'c','amount':10"),
                ok.replace("'customerRef':'c'", "'customerRef':'c','outcome':'DECLINED'"),
                ok.replace("'ticketIds'", "'ticketIds':['T9'],'ticketIds'"),
                ok + " x",
                "[]",
                "{}");
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorizations")
    void contractViolationsAnswer400AndAreNotCounted(String invalid) {
        Response response = send(HttpMethod.POST, "/payments", json(invalid), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", "v-1");
        }).assertResponseConforms();
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(get("/control/authorizations/v-1").status()).isEqualTo(404);
    }

    @Test
    void idempotencyKeyIsRequiredBoundedAndEqualToTheAttempt() {
        String valid = body("k-1");
        Response missing = send(HttpMethod.POST, "/payments", valid, headers -> headers.set("X-Api-Key", API_KEY))
                .assertResponseConforms();
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.requestViolations()).anyMatch(v -> v.startsWith("validation.request.parameter.header.missing"));

        Response different = authorizeWithKey("k-2", valid);
        assertThat(different.status()).isEqualTo(400);
        assertThat(different.json().get("message").stringValue()).contains("Idempotency-Key");

        String longId = "k".repeat(81);
        Response tooLong = authorizeWithKey(longId, body(longId));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.requestViolations()).anyMatch(v -> v.contains("maxLength"));

        Response repeated = send(HttpMethod.POST, "/payments", valid, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.addAll("Idempotency-Key", List.of("k-1", "k-1"));
        }).assertResponseConforms();
        assertThat(repeated.status()).isEqualTo(400);
        assertThat(get("/control/authorizations/k-1").status()).isEqualTo(404);

        Response notJson = send(HttpMethod.POST, "/payments", valid, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", "k-1");
            headers.setContentType(MediaType.TEXT_PLAIN);
        }).assertResponseConforms();
        assertThat(notJson.status()).isEqualTo(400);
    }

    private Response authorizeWithKey(String key, String jsonBody) {
        return send(HttpMethod.POST, "/payments", jsonBody, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", key);
        }).assertResponseConforms();
    }

    @Test
    void lengthsCountUnicodeCharacters() {
        String id = "u".repeat(80); // header values are ASCII; body lengths below use multi-byte characters
        String tickets = "['" + "é".repeat(24) + "']";
        Response response = authorize(id, body(id, ORDER, "ü".repeat(128), tickets)).assertConforms();
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void inspectionAnswers404WithoutBodyAndHasNoSideEffects() {
        Response missing = get("/control/authorizations/never");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEmpty();
        assertThat(get("/control/authorizations/never").status()).isEqualTo(404);
        // Inspecting before the first authorization does not create or decide the attempt.
        assertThat(authorize("never", body("never")).json().get("replayed").booleanValue()).isFalse();
        JsonNode once = get("/control/authorizations/never").json();
        assertThat(get("/control/authorizations/never").json()).isEqualTo(once);
        assertThat(once.get("invocations").intValue()).isEqualTo(1);
    }

    @Test
    void pathIdentifierLongerThan80AnswersTheUndeclared400() {
        Response response = send(HttpMethod.GET, "/control/authorizations/" + "x".repeat(81), null,
                headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        // PM-IV-005 accepted this status although API-108 does not declare it.
        assertThat(response.responseViolations()).singleElement().asString().startsWith("validation.response.status.unknown");
    }

    @Test
    void resetForgetsAuthorizations() {
        authorize("a-1", body("a-1"));
        assertThat(get("/control/authorizations/a-1").status()).isEqualTo(200);
        post("/control/reset", null);
        assertThat(get("/control/authorizations/a-1").status()).isEqualTo(404);
        assertThat(authorize("a-1", body("a-1")).json().get("replayed").booleanValue()).isFalse();
    }

    @Test
    void concurrentRepetitionsOverHttpDecideOnce() throws Exception {
        put("/control/defaults", json("{'defaultOutcome':'DECLINED'}"));
        int requests = 24;
        List<JsonNode> replies = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    replies.add(authorize("c-1", body("c-1")).assertConforms().json());
                } catch (Throwable error) {
                    errors.add(error);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(errors).isEmpty();
        assertThat(replies).hasSize(requests);
        assertThat(replies).filteredOn(reply -> !reply.get("replayed").booleanValue()).hasSize(1);
        assertThat(replies).extracting(reply -> reply.get("reasonCode").stringValue()).containsOnly("RULE_DECLINED");
        assertThat(get("/control/authorizations/c-1").json().get("invocations").intValue()).isEqualTo(requests);
    }
}
