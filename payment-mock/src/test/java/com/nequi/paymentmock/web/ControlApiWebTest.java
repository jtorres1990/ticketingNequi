package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.WebTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Control API over HTTP (API-103 to API-107, API-110): contract conformance, PM-IV-005 to PM-IV-008, PM-SPK-006
 * negative cases and the PM-IV-016 tolerance on rule responses.
 */
class ControlApiWebTest extends WebTestSupport {

    static final String SECRET_VALUE = "zz-secret-input-value-zz";

    @ParameterizedTest
    @ValueSource(strings = {
            "{'match':{'orderId':'o-1'},'behaviour':{'type':'APPROVE'}}",
            "{'match':{'ticketId':'T-1'},'behaviour':{'type':'DECLINE'}}",
            "{'match':{'customerRef':'c-1'},'behaviour':{'type':'DECLINE','reasonCode':'INSUFFICIENT_FUNDS'}}",
            "{'match':{'eventId':'e-1'},'behaviour':{'type':'DEFINITIVE_ERROR'}}",
            "{'match':{'orderId':'o-2'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':0,'finalOutcome':'APPROVED'}}",
            "{'match':{'orderId':'o-3'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':3,'finalOutcome':'DECLINED','reasonCode':'CARD_DECLINED'}}",
            "{'match':{'orderId':'o-4'},'behaviour':{'type':'LATENCY','addedLatencyMs':0}}",
            "{'match':{'orderId':'o-5'},'behaviour':{'type':'LATENCY','addedLatencyMs':60000,'finalOutcome':'DECLINED','reasonCode':'RULE_DECLINED'}}",
            "{'match':{'orderId':''},'behaviour':{'type':'APPROVE'}}"
    })
    void createsEveryMatcherAndBehaviourAndEchoesTheRuleAsCreated(String input) {
        Response created = post("/control/rules", json(input));
        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.json();
        assertThat(body.get("ruleId").stringValue()).matches("rule-[0-9]+");
        JsonNode expected = JSON.readTree(json(input));
        assertThat(body.get("match")).isEqualTo(expected.get("match"));
        assertThat(body.get("behaviour")).isEqualTo(expected.get("behaviour"));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("ruleId", "match", "behaviour");

        Response listed = get("/control/rules");
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.json()).singleElement().isEqualTo(body);
    }

    @Test
    void listsRulesInEffectiveOrderWithMostRecentFirstWithinEachMatcher() {
        List<String> ids = new ArrayList<>();
        for (String match : List.of("{'eventId':'e'}", "{'orderId':'o'}", "{'customerRef':'c'}", "{'ticketId':'t'}",
                "{'orderId':'o'}", "{'eventId':'e'}")) {
            ids.add(post("/control/rules", json("{'match':" + match + ",'behaviour':{'type':'APPROVE'}}")).json()
                    .get("ruleId").stringValue());
        }
        List<String> listed = new ArrayList<>();
        get("/control/rules").json().forEach(rule -> listed.add(rule.get("ruleId").stringValue()));
        assertThat(listed).containsExactly(ids.get(4), ids.get(1), ids.get(3), ids.get(2), ids.get(5), ids.get(0));
    }

    @Test
    void ruleIdentifiersAreNotReusedAfterReset() {
        String before = post("/control/rules", json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}")).json()
                .get("ruleId").stringValue();
        assertThat(post("/control/reset", null).status()).isEqualTo(204);
        assertThat(get("/control/rules").json()).isEmpty();
        String after = post("/control/rules", json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}")).json()
                .get("ruleId").stringValue();
        assertThat(Long.parseLong(after.substring(5))).isGreaterThan(Long.parseLong(before.substring(5)));
        // Deleting the old identifier after the reset does not remove the new rule.
        assertThat(delete("/control/rules/" + before).status()).isEqualTo(204);
        assertThat(get("/control/rules").json()).hasSize(1);
    }

    @Test
    void deletesOneRuleIdempotentlyAndAllRules() {
        String id = post("/control/rules", json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}")).json()
                .get("ruleId").stringValue();
        post("/control/rules", json("{'match':{'eventId':'e'},'behaviour':{'type':'APPROVE'}}"));

        assertThat(delete("/control/rules/" + id).status()).isEqualTo(204);
        assertThat(delete("/control/rules/" + id).status()).isEqualTo(204);
        assertThat(delete("/control/rules/does-not-exist").status()).isEqualTo(204);
        assertThat(get("/control/rules").json()).hasSize(1);

        Response deletedAll = delete("/control/rules");
        assertThat(deletedAll.status()).isEqualTo(204);
        assertThat(deletedAll.body()).isEmpty();
        assertThat(delete("/control/rules").status()).isEqualTo(204);
        assertThat(get("/control/rules").json()).isEmpty();
    }

    @Test
    void setsDefaultsReplacingTheWholeObject() {
        Response full = put("/control/defaults", json("{'defaultOutcome':'DECLINED','declinePercentage':40}"));
        assertThat(full.status()).isEqualTo(200);
        assertThat(full.json()).isEqualTo(JSON.readTree(json("{'defaultOutcome':'DECLINED','declinePercentage':40}")));

        Response withoutPercentage = put("/control/defaults", json("{'defaultOutcome':'APPROVED'}"));
        assertThat(withoutPercentage.json())
                .isEqualTo(JSON.readTree(json("{'defaultOutcome':'APPROVED','declinePercentage':0}")));

        assertThat(put("/control/defaults", json("{'defaultOutcome':'APPROVED','declinePercentage':0}")).status())
                .isEqualTo(200);
        assertThat(put("/control/defaults", json("{'defaultOutcome':'APPROVED','declinePercentage':100}")).json()
                .get("declinePercentage").intValue()).isEqualTo(100);
    }

    @Test
    void resetAnswers204WithoutBody() {
        post("/control/rules", json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}"));
        Response reset = post("/control/reset", null);
        assertThat(reset.status()).isEqualTo(204);
        assertThat(reset.body()).isEmpty();
        assertThat(get("/control/rules").json()).isEmpty();
    }

    static Stream<String> invalidRules() {
        return Stream.of(
                // structure
                "{'match':{},'behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':'o','eventId':'e'},'behaviour':{'type':'APPROVE'}}",
                "{'behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':'o'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'},'ruleId':'rule-1'}",
                "{'match':{'orderId':'o','amount':'" + SECRET_VALUE + "'},'behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE','card':'" + SECRET_VALUE + "'}}",
                "{'match':'o','behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':'o'},'behaviour':'APPROVE'}",
                "{'match':null,'behaviour':{'type':'APPROVE'}}",
                // types and coercions
                "{'match':{'orderId':7},'behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':null},'behaviour':{'type':'APPROVE'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':null}}",
                "{'match':{'orderId':'o'},'behaviour':{}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'approve'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'" + SECRET_VALUE + "'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':'3','finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':3.5,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':3.0,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':-1,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':99999999999999999999,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':null,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':true,'finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':60001}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':-1}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':'10'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':10,'finalOutcome':'MAYBE'}}",
                // per-type combinations (PM-IV-007)
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE','reasonCode':'CARD_DECLINED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE','finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','transientFailures':1}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','addedLatencyMs':1}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DEFINITIVE_ERROR','reasonCode':'CARD_DECLINED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','finalOutcome':'APPROVED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':1}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':1,'finalOutcome':'APPROVED','reasonCode':'CARD_DECLINED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':1,'finalOutcome':'APPROVED','addedLatencyMs':5}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':5,'reasonCode':'CARD_DECLINED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'LATENCY','addedLatencyMs':5,'transientFailures':1}}",
                // reason codes
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','reasonCode':'PERCENTAGE_DECLINED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','reasonCode':'ATTEMPT_CANCELLED'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','reasonCode':'" + SECRET_VALUE + "'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'DECLINE','reasonCode':5}}",
                // syntax
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'},'match':{'orderId':'p'}}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}} {}",
                "{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}",
                "[{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}]",
                "'" + SECRET_VALUE + "'",
                "null",
                "   ");
    }

    @ParameterizedTest
    @MethodSource("invalidRules")
    void rejectsInvalidRulesWith400WithoutEchoingInput(String input) {
        Response response = send(HttpMethod.POST, "/control/rules", json(input), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.setContentType(MediaType.APPLICATION_JSON);
        });
        assertValidationError(response);
        assertThat(get("/control/rules").json()).isEmpty();
    }

    static Stream<String> invalidDefaults() {
        return Stream.of(
                "{}",
                "{'declinePercentage':10}",
                "{'defaultOutcome':'MAYBE'}",
                "{'defaultOutcome':'approved'}",
                "{'defaultOutcome':null}",
                "{'defaultOutcome':1}",
                "{'defaultOutcome':'APPROVED','declinePercentage':101}",
                "{'defaultOutcome':'APPROVED','declinePercentage':-1}",
                "{'defaultOutcome':'APPROVED','declinePercentage':'10'}",
                "{'defaultOutcome':'APPROVED','declinePercentage':10.5}",
                "{'defaultOutcome':'APPROVED','declinePercentage':null}",
                "{'defaultOutcome':'APPROVED','extra':'" + SECRET_VALUE + "'}",
                "{'defaultOutcome':'APPROVED','defaultOutcome':'DECLINED'}",
                "['APPROVED']",
                "");
    }

    @ParameterizedTest
    @MethodSource("invalidDefaults")
    void rejectsInvalidDefaultsWith400(String input) {
        put("/control/defaults", json("{'defaultOutcome':'DECLINED','declinePercentage':7}"));
        Response response = send(HttpMethod.PUT, "/control/defaults", input.isEmpty() ? null : json(input), headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.setContentType(MediaType.APPLICATION_JSON);
        });
        assertValidationError(response);
    }

    @Test
    void rejectsBodiesThatAreNotDeclaredAsJson() throws Exception {
        String rule = json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}");
        for (MediaType type : List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_XML, MediaType.APPLICATION_FORM_URLENCODED)) {
            Response response = send(HttpMethod.POST, "/control/rules", rule, headers -> {
                headers.set("X-Api-Key", API_KEY);
                headers.setContentType(type);
            });
            assertThat(response.status()).as(type.toString()).isEqualTo(400);
            assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        }
        Response withoutType = send(HttpMethod.POST, "/control/rules", null, headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(withoutType.status()).isEqualTo(400);
        // WebClient refuses to send a malformed Content-Type, so the JDK client is used for this case.
        java.net.http.HttpResponse<String> invalidType = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/control/defaults"))
                        .header("X-Api-Key", API_KEY).header("Content-Type", "not a media type")
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json("{'defaultOutcome':'APPROVED'}")))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(invalidType.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(invalidType.body()).get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(get("/control/rules").json()).isEmpty();
    }

    @Test
    void rejectsBodiesLargerThanTheCodecLimitWith400() {
        String huge = json("{'match':{'orderId':'" + "x".repeat(300 * 1024) + "'},'behaviour':{'type':'APPROVE'}}");
        Response response = send(HttpMethod.POST, "/control/rules", huge, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.setContentType(MediaType.APPLICATION_JSON);
        });
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void frameworkErrorsUseTheErrorBody() {
        Response methodNotAllowed = send(HttpMethod.PUT, "/control/rules", null, headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(methodNotAllowed.status()).isEqualTo(405);
        assertThat(methodNotAllowed.json().get("code").stringValue()).isEqualTo("METHOD_NOT_ALLOWED");

        Response notAcceptable = send(HttpMethod.GET, "/control/rules", null, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.setAccept(List.of(MediaType.TEXT_HTML));
        });
        assertThat(notAcceptable.status()).isEqualTo(406);
        assertThat(notAcceptable.json().get("code").stringValue()).isEqualTo("NOT_ACCEPTABLE");

        Response notFound = send(HttpMethod.GET, "/control/nothing", null, headers -> headers.set("X-Api-Key", API_KEY));
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.json().get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void concurrentRuleCreationOverHttpLosesNothing() throws Exception {
        int requests = 40;
        List<Thread> threads = new ArrayList<>();
        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < requests; i++) {
            int index = i;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    statuses.add(post("/control/rules",
                            json("{'match':{'orderId':'o-" + index + "'},'behaviour':{'type':'APPROVE'}}")).status());
                } catch (Throwable error) {
                    errors.add(error);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(errors).isEmpty();
        assertThat(statuses).hasSize(requests).containsOnly(201);
        JsonNode listed = get("/control/rules").json();
        assertThat(listed).hasSize(requests);
    }

    private static void assertValidationError(Response response) {
        assertThat(response.status()).isEqualTo(400);
        response.assertResponseConforms();
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.body()).doesNotContain(SECRET_VALUE).doesNotContain("Exception").doesNotContain("at com.");
    }
}
