package com.nequi.ticketing.infrastructure.adapter.sqs;

import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.EVENT_ID;
import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.infrastructure.adapter.sqs.MessageCodec.Decoded;
import com.nequi.ticketing.infrastructure.adapter.sqs.MessageCodec.MessageAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.util.context.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessageCodecTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> ORDER_ATTRIBUTES =
            Map.of("messageType", "OrderProcessingRequested", "schemaVersion", "1");

    @Test
    @DisplayName("MSG-001 version 1 is encoded with its six fields and the messageType, schemaVersion, publisher and traceparent attributes")
    void encodesOrderProcessingRequested() {
        OrderProcessingRequested message = new OrderProcessingRequested(ORDER_ID, EVENT_ID,
                Instant.parse("2026-10-04T10:00:00Z"), "corr-1", OrderProcessingRequested.Publisher.API);

        MessageCodec.OutboundMessage encoded = MessageCodec.encode(message, Optional.of("00-abc-def-01"));

        JsonNode body = JSON.readTree(encoded.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "schemaVersion", "messageType", "orderId", "eventId", "occurredAt", "correlationId");
        assertThat(body.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(body.get("messageType").stringValue()).isEqualTo("OrderProcessingRequested");
        assertThat(body.get("orderId").stringValue()).isEqualTo(ORDER_ID);
        assertThat(body.get("eventId").stringValue()).isEqualTo(EVENT_ID);
        assertThat(body.get("occurredAt").stringValue()).isEqualTo("2026-10-04T10:00:00Z");
        assertThat(body.get("correlationId").stringValue()).isEqualTo("corr-1");
        assertThat(encoded.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "messageType", new MessageAttribute("String", "OrderProcessingRequested"),
                "schemaVersion", new MessageAttribute("Number", "1"),
                "publisher", new MessageAttribute("String", "api"),
                "traceparent", new MessageAttribute("String", "00-abc-def-01")));
    }

    @Test
    @DisplayName("MSG-001 published by the sweep carries publisher=sweep and no trace attribute without trace context")
    void encodesSweepPublisherWithoutTrace() {
        OrderProcessingRequested message = new OrderProcessingRequested(ORDER_ID, EVENT_ID,
                Instant.parse("2026-10-04T10:00:00Z"), "cycle-9", OrderProcessingRequested.Publisher.SWEEP);

        MessageCodec.OutboundMessage encoded = MessageCodec.encode(message, Optional.of(" "));

        assertThat(encoded.attributes()).containsEntry("publisher", new MessageAttribute("String", "sweep"))
                .doesNotContainKey("traceparent").doesNotContainKey("correlationId");
    }

    @Test
    @DisplayName("MSG-002 version 1 carries only the eventId in the body and the correlationId as attribute")
    void encodesEventProvisioningRequested() {
        MessageCodec.OutboundMessage encoded =
                MessageCodec.encode(new EventProvisioningRequested(EVENT_ID, "corr-2"), Optional.empty());

        JsonNode body = JSON.readTree(encoded.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "messageType", "eventId");
        assertThat(body.get("messageType").stringValue()).isEqualTo("EventProvisioningRequested");
        assertThat(body.get("eventId").stringValue()).isEqualTo(EVENT_ID);
        assertThat(encoded.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "messageType", new MessageAttribute("String", "EventProvisioningRequested"),
                "schemaVersion", new MessageAttribute("Number", "1"),
                "correlationId", new MessageAttribute("String", "corr-2")));
    }

    @Test
    @DisplayName("MSG-001 round trip: an encoded message decodes to its orderId and correlationId; unknown fields are ignored")
    void decodesOrder() {
        String body = SqsTestMessages.orderBody(ORDER_ID).replace("}", ",\"futureField\":true}");

        Decoded<MessageCodec.OrderRequest> decoded = MessageCodec.decodeOrder(body, ORDER_ATTRIBUTES);

        assertThat(decoded).isEqualTo(Decoded.readable(new MessageCodec.OrderRequest(ORDER_ID, "corr-1")));
        assertThat(MessageCodec.decodeOrder(SqsTestMessages.orderBody(ORDER_ID.toUpperCase()), Map.of()))
                .isInstanceOf(Decoded.Readable.class);
    }

    @ParameterizedTest(name = "ERR-005 MSG-001 is unreadable (poison) when {0}")
    @ValueSource(strings = {
            "not json", "array", "string", "unknown type", "type missing", "version 2", "version as text",
            "version missing", "orderId missing", "orderId malformed", "orderId blank", "orderId number",
            "eventId missing", "occurredAt missing", "occurredAt malformed", "correlationId missing",
            "attribute type mismatch", "attribute version mismatch"})
    void unreadableOrders(String scenario) {
        String valid = SqsTestMessages.orderBody(ORDER_ID);
        Map<String, String> attributes = ORDER_ATTRIBUTES;
        String body = switch (scenario) {
            case "not json" -> "{not json";
            case "array" -> "[1,2]";
            case "string" -> "\"text\"";
            case "unknown type" -> valid.replace("OrderProcessingRequested", "OrderCancelled");
            case "type missing" -> valid.replace("\"messageType\":\"OrderProcessingRequested\",", "");
            case "version 2" -> valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "version as text" -> valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"");
            case "version missing" -> valid.replace("\"schemaVersion\":1,", "");
            case "orderId missing" -> valid.replace("\"orderId\":\"" + ORDER_ID + "\",", "");
            case "orderId malformed" -> valid.replace(ORDER_ID, "order-1");
            case "orderId blank" -> valid.replace(ORDER_ID, " ");
            case "orderId number" -> valid.replace("\"" + ORDER_ID + "\"", "12");
            case "eventId missing" -> valid.replace("\"eventId\":\"" + EVENT_ID + "\",", "");
            case "occurredAt missing" -> valid.replace("\"occurredAt\":\"2026-10-04T10:00:00Z\",", "");
            case "occurredAt malformed" -> valid.replace("2026-10-04T10:00:00Z", "yesterday");
            case "correlationId missing" -> valid.replace(",\"correlationId\":\"corr-1\"", "");
            default -> valid;
        };
        if (scenario.equals("attribute type mismatch")) {
            attributes = Map.of("messageType", "EventProvisioningRequested");
        } else if (scenario.equals("attribute version mismatch")) {
            attributes = Map.of("schemaVersion", "2");
        }

        assertThat(MessageCodec.decodeOrder(body, attributes)).isInstanceOf(Decoded.Unreadable.class);
    }

    @Test
    @DisplayName("ERR-005 a missing body is unreadable")
    void nullBodyIsUnreadable() {
        assertThat(MessageCodec.decodeOrder(null, Map.of()))
                .isEqualTo(Decoded.unreadable("body is not a JSON object"));
    }

    @Test
    @DisplayName("MSG-002 decodes the eventId and takes the correlationId from the attribute; missing either is unreadable")
    void decodesProvisioning() {
        String body = SqsTestMessages.provisioningBody(EVENT_ID);

        assertThat(MessageCodec.decodeProvisioning(body, Map.of("correlationId", "corr-2")))
                .isEqualTo(Decoded.readable(new MessageCodec.ProvisioningRequest(EVENT_ID, "corr-2")));
        assertThat(MessageCodec.decodeProvisioning(body, Map.of()))
                .isEqualTo(Decoded.unreadable("missing correlationId attribute"));
        assertThat(MessageCodec.decodeProvisioning(body, Map.of("correlationId", "")))
                .isInstanceOf(Decoded.Unreadable.class);
        assertThat(MessageCodec.decodeProvisioning(body.replace(EVENT_ID, "x"), Map.of("correlationId", "c")))
                .isEqualTo(Decoded.unreadable("missing or malformed eventId"));
        assertThat(MessageCodec.decodeProvisioning(SqsTestMessages.orderBody(ORDER_ID), Map.of("correlationId", "c")))
                .isEqualTo(Decoded.unreadable("unknown message type"));
    }

    @Test
    @DisplayName("TC-008 the trace context moves between the Reactor context and the traceparent attribute")
    void traceContext() {
        assertThat(TraceContext.from(Context.of(TraceContext.KEY, "00-1-2-01"))).contains("00-1-2-01");
        assertThat(TraceContext.from(Context.empty())).isEmpty();
        assertThat(TraceContext.with(Context.empty(), "00-1-2-01").hasKey(TraceContext.KEY)).isTrue();
        assertThat(TraceContext.with(Context.empty(), null).isEmpty()).isTrue();
        assertThat(TraceContext.with(Context.empty(), "").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Codec value types reject missing parts")
    void valueTypes() {
        assertThatThrownBy(() -> new MessageAttribute(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageAttribute("String", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageCodec.OutboundMessage(null, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Decoded.readable(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Decoded.unreadable(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MessageCodec.encode((OrderProcessingRequested) null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MessageCodec.encode((EventProvisioningRequested) null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("The default SQS observation hook ignores every notification")
    void defaultEventsAreNoOps() {
        SqsEvents.NONE.publicationFailed("q", new RuntimeException());
        SqsEvents.NONE.unreadableMessage("q", "m", "r");
        SqsEvents.NONE.processingFailed("q", "m", new RuntimeException());
        SqsEvents.NONE.messageActionFailed("q", "m", new RuntimeException());
        SqsEvents.NONE.receiveFailed("q", new RuntimeException(), Duration.ZERO);
        SqsEvents.NONE.heartbeatStopped("q", "m");
        assertThat(SqsEvents.NONE).isNotNull();
    }
}
