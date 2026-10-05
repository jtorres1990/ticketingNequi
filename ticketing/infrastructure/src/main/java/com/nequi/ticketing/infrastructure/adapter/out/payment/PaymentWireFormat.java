package com.nequi.ticketing.infrastructure.adapter.out.payment;

import com.nequi.ticketing.application.port.out.AuthorizationOutcome;
import com.nequi.ticketing.application.port.out.CancellationOutcome;
import com.nequi.ticketing.application.port.out.PaymentAuthorization;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Own wire types of the payment adapter (ADR-034 boundary rule 7: no code shared with {@code payment-mock};
 * the adapter only knows {@code payment-mock.openapi.v1.yaml}):
 * <ul>
 *   <li>{@code AuthorizationRequest} of API-101: {@code paymentAttemptId}, {@code orderId}, {@code eventId},
 *       {@code customerRef} (JWT subject, ADR-030) and {@code ticketIds};</li>
 *   <li>{@code AuthorizationResult}: {@code APPROVED} or {@code DECLINED} (including
 *       {@code ATTEMPT_CANCELLED}, BR-034) with {@code providerReference} and {@code reasonCode};</li>
 *   <li>{@code CancellationResult} of API-102: {@code REVERSED}, {@code VOIDED} or
 *       {@code REGISTERED_BEFORE_CHARGE}.</li>
 * </ul>
 * Classification of a response (ADR-030 "Classification in ticketing"): 2xx with a valid body is the
 * result; any 4xx is a definitive contract error; 5xx, any other status or a 2xx whose body does not match
 * the contract is transient (the result is unknown, so it is never taken as a decline). Unknown fields are
 * ignored.
 */
final class PaymentWireFormat {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PaymentWireFormat() {
    }

    static String authorizationRequest(PaymentAuthorization request) {
        ObjectNode body = JSON.createObjectNode()
                .put("paymentAttemptId", request.paymentAttemptId())
                .put("orderId", request.orderId())
                .put("eventId", request.eventId())
                .put("customerRef", request.customerRef());
        ArrayNode tickets = body.putArray("ticketIds");
        request.ticketIds().forEach(tickets::add);
        return JSON.writeValueAsString(body);
    }

    /** @throws PaymentCallFailure when the response is not an authorization result */
    static AuthorizationOutcome authorizationOutcome(String paymentAttemptId, PaymentTransport.HttpReply reply) {
        JsonNode body = successBody(reply);
        String providerReference = text(body, "providerReference")
                .filter(value -> !value.isBlank())
                .orElseThrow(PaymentWireFormat::invalid);
        requireAttempt(body, paymentAttemptId);
        String status = text(body, "status").orElseThrow(PaymentWireFormat::invalid);
        return switch (status) {
            case "APPROVED" -> new AuthorizationOutcome.Approved(providerReference);
            case "DECLINED" -> new AuthorizationOutcome.Declined(providerReference, text(body, "reasonCode").orElse(null));
            default -> throw invalid();
        };
    }

    /** @throws PaymentCallFailure when the response is not a cancellation result */
    static CancellationOutcome cancellationOutcome(String paymentAttemptId, PaymentTransport.HttpReply reply) {
        JsonNode body = successBody(reply);
        requireAttempt(body, paymentAttemptId);
        String status = text(body, "cancellationStatus").orElseThrow(PaymentWireFormat::invalid);
        return switch (status) {
            case "REVERSED" -> new CancellationOutcome.Cancelled(CancellationOutcome.CancellationStatus.REVERSED);
            case "VOIDED" -> new CancellationOutcome.Cancelled(CancellationOutcome.CancellationStatus.VOIDED);
            case "REGISTERED_BEFORE_CHARGE" ->
                    new CancellationOutcome.Cancelled(CancellationOutcome.CancellationStatus.REGISTERED_BEFORE_CHARGE);
            default -> throw invalid();
        };
    }

    private static JsonNode successBody(PaymentTransport.HttpReply reply) {
        int status = reply.status();
        if (status >= 400 && status < 500) {
            throw PaymentCallFailure.contract(status);
        }
        if (status < 200 || status >= 300) {
            throw PaymentCallFailure.transientFailure("HTTP_" + status);
        }
        JsonNode body;
        try {
            body = reply.body() == null ? null : JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            throw invalid();
        }
        if (body == null || !body.isObject()) {
            throw invalid();
        }
        return body;
    }

    private static void requireAttempt(JsonNode body, String paymentAttemptId) {
        if (!text(body, "paymentAttemptId").map(paymentAttemptId::equals).orElse(false)) {
            throw invalid();
        }
    }

    private static Optional<String> text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
    }

    private static PaymentCallFailure invalid() {
        return PaymentCallFailure.transientFailure(PaymentCallFailure.INVALID_RESPONSE);
    }
}
