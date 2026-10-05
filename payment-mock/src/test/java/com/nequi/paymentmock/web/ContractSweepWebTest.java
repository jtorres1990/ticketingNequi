package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.Contract;
import com.nequi.paymentmock.support.WebTestSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Contract sweep (G3, ADR-038): every response status declared by {@code payment-mock.openapi.v1.yaml} for API-101 to
 * API-111 is either produced here and validated against the contract, or listed as unreachable with its reason.
 * The declared statuses are read from the contract copy, so a new status in the contract fails this test.
 */
class ContractSweepWebTest extends WebTestSupport {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b";

    /** Declared but intentionally not producible by any rule (approved decisions). */
    static final Map<String, String> UNREACHABLE = Map.of(
            "API-101 500", "Only on an unexpected internal error (PM-IV-005); simulated failures use 503 or 422 "
                    + "(PM-IV-009). The 500 body is verified by ApiErrorHandlerTest",
            "API-102 500", "Cancellation failures are not simulated (PM-IV-015); only an unexpected internal error",
            "API-102 503", "Cancellation failures are not simulated (PM-IV-015); demonstrated by stopping the "
                    + "container (ADR-038 resilience scenario 2)");

    static String body(String attemptId) {
        return json("{'paymentAttemptId':'" + attemptId + "','orderId':'" + ORDER + "','eventId':'" + EVENT
                + "','customerRef':'c-1','ticketIds':['T1']}");
    }

    Response authorize(String attemptId, String jsonBody) {
        return send(HttpMethod.POST, "/payments", jsonBody, headers -> {
            headers.set("X-Api-Key", API_KEY);
            headers.set("Idempotency-Key", attemptId);
        });
    }

    Response unauthenticated(HttpMethod method, String path, String jsonBody) {
        return send(method, path, jsonBody, headers -> headers.set("Idempotency-Key", "s-1"));
    }

    Response rule(String behaviour) {
        return post("/control/rules", json("{'match':{'orderId':'" + ORDER + "'},'behaviour':" + behaviour + "}"));
    }

    Map<String, Supplier<Response>> scenarios() {
        Map<String, Supplier<Response>> scenarios = new LinkedHashMap<>();
        scenarios.put("API-101 200", () -> authorize("s-1", body("s-1")));
        scenarios.put("API-101 400", () -> authorize("s-1", body("s-1").replace(ORDER, "not-a-uuid")));
        scenarios.put("API-101 401", () -> unauthenticated(HttpMethod.POST, "/payments", body("s-1")));
        scenarios.put("API-101 422", () -> {
            rule("{'type':'DEFINITIVE_ERROR'}");
            return authorize("s-1", body("s-1"));
        });
        scenarios.put("API-101 503", () -> {
            rule("{'type':'TRANSIENT_THEN_OUTCOME','transientFailures':1,'finalOutcome':'APPROVED'}");
            return authorize("s-1", body("s-1"));
        });
        scenarios.put("API-102 200", () -> post("/payments/s-1/cancellation", null));
        scenarios.put("API-102 401", () -> unauthenticated(HttpMethod.POST, "/payments/s-1/cancellation", null));
        scenarios.put("API-103 200", () -> {
            rule("{'type':'APPROVE'}");
            return get("/control/rules");
        });
        scenarios.put("API-103 401", () -> unauthenticated(HttpMethod.GET, "/control/rules", null));
        scenarios.put("API-104 201", () -> rule("{'type':'LATENCY','addedLatencyMs':10}"));
        scenarios.put("API-104 400", () -> send(HttpMethod.POST, "/control/rules",
                json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'},'extra':1}"),
                headers -> headers.set("X-Api-Key", API_KEY)));
        scenarios.put("API-104 401", () -> unauthenticated(HttpMethod.POST, "/control/rules",
                json("{'match':{'orderId':'o'},'behaviour':{'type':'APPROVE'}}")));
        scenarios.put("API-105 204", () -> delete("/control/rules"));
        scenarios.put("API-105 401", () -> unauthenticated(HttpMethod.DELETE, "/control/rules", null));
        scenarios.put("API-106 204", () -> delete("/control/rules/rule-0"));
        scenarios.put("API-106 401", () -> unauthenticated(HttpMethod.DELETE, "/control/rules/rule-0", null));
        scenarios.put("API-107 200", () -> put("/control/defaults", json("{'defaultOutcome':'DECLINED','declinePercentage':5}")));
        scenarios.put("API-107 400", () -> send(HttpMethod.PUT, "/control/defaults", json("{'defaultOutcome':'NO'}"),
                headers -> headers.set("X-Api-Key", API_KEY)));
        scenarios.put("API-107 401", () -> unauthenticated(HttpMethod.PUT, "/control/defaults",
                json("{'defaultOutcome':'APPROVED'}")));
        scenarios.put("API-108 200", () -> {
            authorize("s-1", body("s-1"));
            return get("/control/authorizations/s-1");
        });
        scenarios.put("API-108 401", () -> unauthenticated(HttpMethod.GET, "/control/authorizations/s-1", null));
        scenarios.put("API-108 404", () -> get("/control/authorizations/s-1"));
        scenarios.put("API-109 200", () -> {
            post("/payments/s-1/cancellation", null);
            return get("/control/cancellations/s-1");
        });
        scenarios.put("API-109 401", () -> unauthenticated(HttpMethod.GET, "/control/cancellations/s-1", null));
        scenarios.put("API-109 404", () -> get("/control/cancellations/s-1"));
        scenarios.put("API-110 204", () -> post("/control/reset", null));
        scenarios.put("API-110 401", () -> unauthenticated(HttpMethod.POST, "/control/reset", null));
        scenarios.put("API-111 200", () -> send(HttpMethod.GET, "/health", null, headers -> { }));
        return scenarios;
    }

    static Set<String> declaredResponses() {
        OpenAPI api = new OpenAPIV3Parser().readContents(Contract.text()).getOpenAPI();
        Set<String> declared = new TreeSet<>();
        for (PathItem item : api.getPaths().values()) {
            for (Operation operation : item.readOperations()) {
                String id = (String) operation.getExtensions().get("x-id");
                operation.getResponses().keySet().forEach(status -> declared.add(id + " " + status));
            }
        }
        return declared;
    }

    @Test
    void everyDeclaredResponseIsProducedAndConformsOrIsJustifiedAsUnreachable() {
        Set<String> declared = declaredResponses();
        Map<String, Supplier<Response>> scenarios = scenarios();
        Set<String> accounted = new TreeSet<>(scenarios.keySet());
        accounted.addAll(UNREACHABLE.keySet());
        assertThat(accounted).as("scenarios + unreachable must equal the declared responses").isEqualTo(declared);
        assertThat(declared).hasSize(scenarios.size() + UNREACHABLE.size());

        for (Map.Entry<String, Supplier<Response>> scenario : scenarios.entrySet()) {
            post("/control/reset", null);
            Response response = scenario.getValue().get();
            String expectedStatus = scenario.getKey().substring(scenario.getKey().indexOf(' ') + 1);
            assertThat(response.status()).as(scenario.getKey()).isEqualTo(Integer.parseInt(expectedStatus));
            if (expectedStatus.equals("400")) {
                // The request is invalid on purpose; the response must conform and the validator must see it too.
                response.assertResponseConforms();
                assertThat(response.requestViolations()).as(scenario.getKey()).isNotEmpty();
            } else {
                assertThat(response.violations()).as(scenario.getKey()).isEmpty();
            }
        }
    }
}
