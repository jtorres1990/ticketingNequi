package com.nequi.ticketing.infrastructure.adapter.sqs;

import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wire format of MSG-001 {@code OrderProcessingRequested} and MSG-002 {@code EventProvisioningRequested},
 * schema version 1 ({@code ticketing.messaging.v2.md} §2): JSON body plus the message attributes
 * {@code messageType}, {@code schemaVersion}, trace context ({@code traceparent}) and {@code publisher}
 * (MSG-001) or {@code correlationId} (MSG-002).
 *
 * <p>Decoding is strict about the schema and lenient about additions: an unknown type or version, a body
 * that is not a JSON object, a required field that is missing or malformed, or attributes that contradict
 * the body make the message unreadable (poison, rule 1 of §5.1 / §5.2); unknown fields are ignored
 * ("campos nuevos opcionales").
 */
public final class MessageCodec {

    public static final String ORDER_PROCESSING_REQUESTED = "OrderProcessingRequested";
    public static final String EVENT_PROVISIONING_REQUESTED = "EventProvisioningRequested";
    public static final int SCHEMA_VERSION = 1;

    public static final String ATTRIBUTE_MESSAGE_TYPE = "messageType";
    public static final String ATTRIBUTE_SCHEMA_VERSION = "schemaVersion";
    public static final String ATTRIBUTE_PUBLISHER = "publisher";
    public static final String ATTRIBUTE_CORRELATION_ID = "correlationId";
    public static final String ATTRIBUTE_TRACE_PARENT = "traceparent";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private MessageCodec() {
    }

    // ------------------------------------------------------------------ encoding

    public static OutboundMessage encode(OrderProcessingRequested message, Optional<String> traceParent) {
        Objects.requireNonNull(message, "message");
        ObjectNode body = JSON.createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("messageType", ORDER_PROCESSING_REQUESTED)
                .put("orderId", message.orderId())
                .put("eventId", message.eventId())
                .put("occurredAt", message.occurredAt().toString())
                .put("correlationId", message.correlationId());
        Map<String, MessageAttribute> attributes = baseAttributes(ORDER_PROCESSING_REQUESTED, traceParent);
        attributes.put(ATTRIBUTE_PUBLISHER, MessageAttribute.string(switch (message.publisher()) {
            case API -> "api";
            case SWEEP -> "sweep";
        }));
        return new OutboundMessage(JSON.writeValueAsString(body), attributes);
    }

    public static OutboundMessage encode(EventProvisioningRequested message, Optional<String> traceParent) {
        Objects.requireNonNull(message, "message");
        ObjectNode body = JSON.createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("messageType", EVENT_PROVISIONING_REQUESTED)
                .put("eventId", message.eventId());
        Map<String, MessageAttribute> attributes = baseAttributes(EVENT_PROVISIONING_REQUESTED, traceParent);
        attributes.put(ATTRIBUTE_CORRELATION_ID, MessageAttribute.string(message.correlationId()));
        return new OutboundMessage(JSON.writeValueAsString(body), attributes);
    }

    private static Map<String, MessageAttribute> baseAttributes(String messageType, Optional<String> traceParent) {
        Map<String, MessageAttribute> attributes = new LinkedHashMap<>();
        attributes.put(ATTRIBUTE_MESSAGE_TYPE, MessageAttribute.string(messageType));
        attributes.put(ATTRIBUTE_SCHEMA_VERSION, MessageAttribute.number(Integer.toString(SCHEMA_VERSION)));
        traceParent.filter(value -> !value.isBlank())
                .ifPresent(value -> attributes.put(ATTRIBUTE_TRACE_PARENT, MessageAttribute.string(value)));
        return attributes;
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes MSG-001; {@code attributes} are the string values of the received message attributes. */
    public static Decoded<OrderRequest> decodeOrder(String body, Map<String, String> attributes) {
        return decode(body, attributes, ORDER_PROCESSING_REQUESTED, node -> {
            String orderId = uuid(node, "orderId");
            if (orderId == null) {
                return Decoded.unreadable("missing or malformed orderId");
            }
            if (uuid(node, "eventId") == null) {
                return Decoded.unreadable("missing or malformed eventId");
            }
            if (instant(node, "occurredAt") == null) {
                return Decoded.unreadable("missing or malformed occurredAt");
            }
            String correlationId = text(node, "correlationId");
            if (correlationId == null) {
                return Decoded.unreadable("missing correlationId");
            }
            return Decoded.readable(new OrderRequest(orderId, correlationId));
        });
    }

    /** Decodes MSG-002; the correlation identifier travels as a message attribute. */
    public static Decoded<ProvisioningRequest> decodeProvisioning(String body, Map<String, String> attributes) {
        return decode(body, attributes, EVENT_PROVISIONING_REQUESTED, node -> {
            String eventId = uuid(node, "eventId");
            if (eventId == null) {
                return Decoded.unreadable("missing or malformed eventId");
            }
            String correlationId = attributes.get(ATTRIBUTE_CORRELATION_ID);
            if (correlationId == null || correlationId.isBlank()) {
                return Decoded.unreadable("missing correlationId attribute");
            }
            return Decoded.readable(new ProvisioningRequest(eventId, correlationId));
        });
    }

    private static <T> Decoded<T> decode(String body, Map<String, String> attributes, String expectedType,
            Function<JsonNode, Decoded<T>> fields) {
        Objects.requireNonNull(attributes, "attributes");
        JsonNode node;
        try {
            node = body == null ? null : JSON.readTree(body);
        } catch (JacksonException malformed) {
            return Decoded.unreadable("body is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            return Decoded.unreadable("body is not a JSON object");
        }
        JsonNode type = node.get("messageType");
        if (type == null || !type.isString() || !expectedType.equals(type.stringValue())) {
            return Decoded.unreadable("unknown message type");
        }
        JsonNode version = node.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != SCHEMA_VERSION) {
            return Decoded.unreadable("unknown schema version");
        }
        String typeAttribute = attributes.get(ATTRIBUTE_MESSAGE_TYPE);
        String versionAttribute = attributes.get(ATTRIBUTE_SCHEMA_VERSION);
        if ((typeAttribute != null && !typeAttribute.equals(expectedType))
                || (versionAttribute != null && !versionAttribute.equals(Integer.toString(SCHEMA_VERSION)))) {
            return Decoded.unreadable("message attributes contradict the body");
        }
        return fields.apply(node);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private static String uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        return UUID_FORMAT.matcher(value).matches() ? value : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    // ------------------------------------------------------------------ types

    /** Body and attributes of a message to send. */
    public record OutboundMessage(String body, Map<String, MessageAttribute> attributes) {

        public OutboundMessage {
            Objects.requireNonNull(body, "body");
            attributes = Map.copyOf(attributes);
        }
    }

    /** A message attribute with its SQS data type ({@code String} or {@code Number}). */
    public record MessageAttribute(String dataType, String value) {

        public MessageAttribute {
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(value, "value");
        }

        static MessageAttribute string(String value) {
            return new MessageAttribute("String", value);
        }

        static MessageAttribute number(String value) {
            return new MessageAttribute("Number", value);
        }
    }

    /** The readable content of MSG-001 the consumer needs. */
    public record OrderRequest(String orderId, String correlationId) {
    }

    /** The readable content of MSG-002 the consumer needs. */
    public record ProvisioningRequest(String eventId, String correlationId) {
    }

    /** Result of decoding: readable content or the reason why the message is unreadable. */
    public sealed interface Decoded<T> permits Decoded.Readable, Decoded.Unreadable {

        static <T> Decoded<T> readable(T content) {
            return new Readable<>(content);
        }

        static <T> Decoded<T> unreadable(String reason) {
            return new Unreadable<>(reason);
        }

        record Readable<T>(T content) implements Decoded<T> {
            public Readable {
                Objects.requireNonNull(content, "content");
            }
        }

        record Unreadable<T>(String reason) implements Decoded<T> {
            public Unreadable {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
