package com.nequi.paymentmock.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.support.ContractValidator.Interaction;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * PM-SPK-003: capability matrix of swagger-request-validator-core 2.46.1 on the contract copy. Each feature has a
 * positive control (accepted) and a negative control (detected). The matrix also pins the PM-IV-016 tolerance to
 * exactly the {@code OutcomeRule} defect.
 */
class OpenApiValidatorCapabilityTest {

    static final String ORDER = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11";
    static final String EVENT = "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c12";
    static final String AUTH = json("{'paymentAttemptId':'a-1','orderId':'" + ORDER + "','eventId':'" + EVENT
            + "','customerRef':'c','ticketIds':['T1']}");
    static final String RULE = json("{'ruleId':'rule-1','match':{'orderId':'x'},'behaviour':{'type':'APPROVE'}}");

    static String json(String text) {
        return text.replace('\'', '"');
    }

    static HttpHeaders headers(String... pairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    static Interaction authorize(HttpHeaders requestHeaders, String requestBody, int status, String responseBody) {
        return new Interaction("POST", "/payments", requestHeaders, bytes(requestBody), status,
                responseBody == null ? new HttpHeaders() : jsonHeaders(), bytes(responseBody));
    }

    /** Response probe paired with a valid request, so that only the response can produce violations. */
    static Interaction response(String method, String path, int status, String body) {
        HttpHeaders requestHeaders = headers("X-Api-Key", "k");
        String requestBody = null;
        if (method.equals("POST") && path.equals("/control/rules")) {
            requestHeaders = jsonHeaders();
            requestHeaders.add("X-Api-Key", "k");
            requestBody = json("{'match':{'orderId':'x'},'behaviour':{'type':'APPROVE'}}");
        } else if (method.equals("POST") && path.equals("/payments")) {
            requestHeaders = validAuthHeaders();
            requestBody = AUTH;
        }
        return new Interaction(method, path, requestHeaders, bytes(requestBody), status,
                body == null ? new HttpHeaders() : jsonHeaders(), bytes(body));
    }

    static byte[] bytes(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    static HttpHeaders validAuthHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.add("X-Api-Key", "k");
        headers.add("Idempotency-Key", "a-1");
        return headers;
    }

    static final String APPROVED = json("{'paymentAttemptId':'a-1','status':'APPROVED','providerReference':'p'}");

    @Test
    void validatesRequestBodiesHeadersAndFormats() {
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH, 200, APPROVED))).isEmpty();

        assertThat(ContractValidator.violations(authorize(validAuthHeaders(),
                AUTH.replace("\"customerRef\":\"c\"", "\"customerRef\":\"c\",\"amount\":3"), 200, APPROVED)))
                .anyMatch(v -> v.startsWith("validation.request.body.schema.additionalProperties"));
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH.replace(ORDER, "nope"), 200, APPROVED)))
                .anyMatch(v -> v.startsWith("validation.request.body.schema.format.uuid"));
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH.replace("[\"T1\"]", "[]"), 200, APPROVED)))
                .anyMatch(v -> v.startsWith("validation.request.body.schema.minItems"));
        HttpHeaders missingKey = jsonHeaders();
        missingKey.add("X-Api-Key", "k");
        assertThat(ContractValidator.violations(authorize(missingKey, AUTH, 200, APPROVED)))
                .anyMatch(v -> v.startsWith("validation.request.parameter.header.missing"));
        HttpHeaders longKey = jsonHeaders();
        longKey.add("X-Api-Key", "k");
        longKey.add("Idempotency-Key", "x".repeat(81));
        assertThat(ContractValidator.violations(authorize(longKey, AUTH, 200, APPROVED)))
                .anyMatch(v -> v.startsWith("validation.request.parameter.schema.maxLength"));
    }

    @Test
    void validatesResponseEnumerationsTypesNullableAndDateTime() {
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH, 200,
                APPROVED.replace("APPROVED", "MAYBE")))).anyMatch(v -> v.contains("schema.enum"));
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH, 200,
                APPROVED.replace("}", ",\"replayed\":\"true\"}")))).anyMatch(v -> v.contains("schema.type"));
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH, 200,
                APPROVED.replace("}", ",\"zzz\":1}")))).anyMatch(v -> v.contains("additionalProperties"));
        // nullable enumeration: an explicit null reasonCode is accepted.
        assertThat(ContractValidator.violations(authorize(validAuthHeaders(), AUTH, 200,
                APPROVED.replace("}", ",\"reasonCode\":null}")))).isEmpty();
        // nullable next to allOf (AuthorizationRecord.result) is NOT accepted: PM-IV-011 omits instead of null.
        assertThat(ContractValidator.violations(response("GET", "/control/authorizations/a", 200,
                json("{'paymentAttemptId':'a','invocations':1,'result':null}")))).isNotEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/authorizations/a", 200,
                json("{'paymentAttemptId':'a','invocations':1,'result':{'paymentAttemptId':'a','status':'APPROVED',"
                        + "'providerReference':'p','cancelled':false}}")))).isEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/cancellations/a", 200,
                json("{'paymentAttemptId':'a','received':1,'cancellationStatus':'VOIDED',"
                        + "'firstReceivedAt':'2026-10-04T10:00:00.123Z'}")))).isEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/cancellations/a", 200,
                json("{'paymentAttemptId':'a','received':1,'cancellationStatus':'VOIDED','firstReceivedAt':'yesterday'}"))))
                .anyMatch(v -> v.contains("format.date-time"));
    }

    @Test
    void detectsUndeclaredStatusesUnexpectedBodiesAndMissingRequiredFields() {
        assertThat(ContractValidator.violations(response("GET", "/control/authorizations/a", 404, null))).isEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/authorizations/a", 404, "{\"code\":\"X\"}")))
                .anyMatch(v -> v.startsWith("validation.response.body.unexpected"));
        assertThat(ContractValidator.violations(response("GET", "/health", 200, "{\"status\":\"UP\"}")))
                .anyMatch(v -> v.startsWith("validation.response.body.unexpected"));
        assertThat(ContractValidator.violations(response("POST", "/payments/a/cancellation", 400, "{\"code\":\"X\"}")))
                .anyMatch(v -> v.startsWith("validation.response.status.unknown"));
        assertThat(ContractValidator.violations(response("POST", "/payments", 503, "{\"message\":\"m\"}")))
                .anyMatch(v -> v.startsWith("validation.response.body.schema.required"));
    }

    @Test
    void doesNotEvaluateTheApiKeySecuritySchemeSoSecurityIsAssertedExplicitly() {
        HttpHeaders withoutKey = jsonHeaders();
        withoutKey.add("Idempotency-Key", "a-1");
        assertThat(ContractValidator.violations(authorize(withoutKey, AUTH, 200, APPROVED))).isEmpty();
    }

    @Test
    void outcomeRuleDefectIsConfirmedAndOnlyThatViolationIsTolerated() {
        Interaction created = response("POST", "/control/rules", 201, RULE);
        assertThat(ContractValidator.violationsWithoutTolerance(created))
                .singleElement().asString().startsWith("validation.response.body.schema.allOf")
                .contains("[\"ruleId\"]").contains("[\"behaviour\",\"match\"]");
        assertThat(ContractValidator.violations(created)).isEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/rules", 200, "[" + RULE + "]"))).isEmpty();

        assertThat(ContractValidator.violations(response("POST", "/control/rules", 201,
                RULE.replace("\"ruleId\":\"rule-1\",", "")))).isNotEmpty();
        assertThat(ContractValidator.violations(response("POST", "/control/rules", 201,
                RULE.replace("{\"ruleId\"", "{\"zzz\":1,\"ruleId\"")))).isNotEmpty();
        assertThat(ContractValidator.violations(response("POST", "/control/rules", 201,
                RULE.replace("APPROVE", "NOPE")))).isNotEmpty();
        assertThat(ContractValidator.violations(response("GET", "/control/rules", 200,
                "[" + RULE.replace("\"orderId\":\"x\"", "\"orderId\":\"x\",\"q\":1") + "]"))).isNotEmpty();
    }
}
