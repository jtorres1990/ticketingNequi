package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Builders of received SQS messages for unit tests. */
public final class SqsTestMessages {

    public static final String ORDER_ID = "6f1c2a8e-3b7d-4c9a-8e21-0d5f7b9a1c34";
    public static final String EVENT_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private SqsTestMessages() {
    }

    public static String orderBody(String orderId) {
        return "{\"schemaVersion\":1,\"messageType\":\"OrderProcessingRequested\",\"orderId\":\"" + orderId
                + "\",\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-10-04T10:00:00Z\",\"correlationId\":\"corr-1\"}";
    }

    public static String provisioningBody(String eventId) {
        return "{\"schemaVersion\":1,\"messageType\":\"EventProvisioningRequested\",\"eventId\":\"" + eventId + "\"}";
    }

    public static Message order(String id, int receiveCount) {
        return message(id, orderBody(ORDER_ID), receiveCount, Map.of("messageType", "OrderProcessingRequested",
                "schemaVersion", "1", "publisher", "api"));
    }

    public static Message provisioning(String id, int receiveCount) {
        return message(id, provisioningBody(EVENT_ID), receiveCount, Map.of("messageType", "EventProvisioningRequested",
                "schemaVersion", "1", "correlationId", "corr-2"));
    }

    public static Message message(String id, String body, int receiveCount, Map<String, String> attributes) {
        Map<String, MessageAttributeValue> values = new HashMap<>();
        attributes.forEach((name, value) -> values.put(name,
                MessageAttributeValue.builder().dataType("String").stringValue(value).build()));
        return Message.builder()
                .messageId(id)
                .receiptHandle("receipt-" + id)
                .body(body)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, Integer.toString(receiveCount)))
                .messageAttributes(values)
                .build();
    }
}
