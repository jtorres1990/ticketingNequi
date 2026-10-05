package com.nequi.paymentmock.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nequi.paymentmock.application.AuthorizationView;
import com.nequi.paymentmock.application.CancellationReply;
import com.nequi.paymentmock.application.CancellationView;
import com.nequi.paymentmock.domain.AttemptPayload;
import com.nequi.paymentmock.domain.AuthorizationResult;
import com.nequi.paymentmock.domain.StableHash;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/** Own request parsing and response shapes of the payment operations and their inspection (ADR-034). */
final class PaymentJson {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final int MAX_ATTEMPT_ID = 80;
    static final Set<String> AUTHORIZATION_FIELDS = Set.of("paymentAttemptId", "orderId", "eventId", "customerRef",
            "ticketIds");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private PaymentJson() {
    }

    /** Validated {@code AuthorizationRequest}. */
    record AuthorizationInput(String paymentAttemptId, AttemptPayload payload) {
    }

    /**
     * {@code AuthorizationResult}. In API-101 {@code replayed} and {@code cancelled} are always present; inside
     * {@code AuthorizationRecord.result} {@code replayed} is omitted; {@code reasonCode} only when declined
     * (PM-IV-011, PM-IV-012).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"paymentAttemptId", "status", "providerReference", "reasonCode", "replayed", "cancelled"})
    record ResultResponse(String paymentAttemptId, String status, String providerReference, String reasonCode,
            Boolean replayed, Boolean cancelled) {

        static ResultResponse of(String paymentAttemptId, AuthorizationResult result, Boolean replayed,
                boolean cancelled) {
            return new ResultResponse(paymentAttemptId, result.status().name(),
                    StableHash.providerReference(paymentAttemptId),
                    result.reasonCode() == null ? null : result.reasonCode().name(), replayed, cancelled);
        }
    }

    /** {@code AuthorizationRecord}: {@code result} omitted while there is none (PM-IV-011). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"paymentAttemptId", "invocations", "result"})
    record AuthorizationRecordResponse(String paymentAttemptId, long invocations, ResultResponse result) {

        static AuthorizationRecordResponse of(AuthorizationView view) {
            return new AuthorizationRecordResponse(view.paymentAttemptId(), view.invocations(),
                    view.result() == null ? null
                            : ResultResponse.of(view.paymentAttemptId(), view.result(), null, view.cancelled()));
        }
    }

    /** {@code CancellationResult}; {@code replayed} always present. */
    @JsonPropertyOrder({"paymentAttemptId", "cancellationStatus", "replayed"})
    record CancellationResponse(String paymentAttemptId, String cancellationStatus, boolean replayed) {

        static CancellationResponse of(CancellationReply reply) {
            return new CancellationResponse(reply.paymentAttemptId(), reply.status().name(), reply.replayed());
        }
    }

    /** {@code CancellationRecord}; {@code firstReceivedAt} in UTC with millisecond precision. */
    @JsonPropertyOrder({"paymentAttemptId", "received", "cancellationStatus", "firstReceivedAt"})
    record CancellationRecordResponse(String paymentAttemptId, long received, String cancellationStatus,
            String firstReceivedAt) {

        static CancellationRecordResponse of(CancellationView view) {
            return new CancellationRecordResponse(view.paymentAttemptId(), view.received(), view.status().name(),
                    view.firstReceivedAt().toString());
        }
    }

    static AuthorizationInput parseAuthorization(HttpHeaders headers, String body) {
        List<String> keys = headers.get(IDEMPOTENCY_KEY);
        if (keys == null || keys.isEmpty()) {
            throw new RequestValidationException("Idempotency-Key header is required");
        }
        if (keys.size() > 1) {
            throw new RequestValidationException("Idempotency-Key header must be sent once");
        }
        String idempotencyKey = keys.getFirst();
        if (JsonRequests.codePoints(idempotencyKey) > MAX_ATTEMPT_ID) {
            throw new RequestValidationException("Idempotency-Key exceeds the maximum length of 80");
        }
        JsonRequests.requireJsonContentType(headers);
        JsonNode root = JsonRequests.parseObject(body);
        JsonRequests.onlyFields(root, "body", AUTHORIZATION_FIELDS);
        String paymentAttemptId = JsonRequests.requiredString(root, "paymentAttemptId", "paymentAttemptId",
                MAX_ATTEMPT_ID);
        String orderId = uuid(root, "orderId");
        String eventId = uuid(root, "eventId");
        String customerRef = JsonRequests.requiredString(root, "customerRef", "customerRef", 128);
        List<String> ticketIds = JsonRequests.requiredStringArray(root, "ticketIds", "ticketIds", 1, 10, 24);
        if (!idempotencyKey.equals(paymentAttemptId)) {
            throw new RequestValidationException("Idempotency-Key must equal paymentAttemptId");
        }
        return new AuthorizationInput(paymentAttemptId, AttemptPayload.of(orderId, eventId, customerRef, ticketIds));
    }

    /** Path parameter {@code paymentAttemptId} of API-102, API-108 and API-109: at most 80 characters (PM-IV-005). */
    static String pathAttemptId(String paymentAttemptId) {
        if (JsonRequests.codePoints(paymentAttemptId) > MAX_ATTEMPT_ID) {
            throw new RequestValidationException("paymentAttemptId exceeds the maximum length of 80");
        }
        return paymentAttemptId;
    }

    private static String uuid(JsonNode root, String field) {
        String value = JsonRequests.requiredString(root, field, field, -1);
        if (!UUID.matcher(value).matches()) {
            throw new RequestValidationException(field + " must be a UUID");
        }
        return value;
    }
}
