package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Explicit attribute mapping helpers (ADR-039: low-level client, no object mapper). */
final class Attributes {

    private Attributes() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue bool(boolean value) {
        return AttributeValue.fromBool(value);
    }

    static AttributeValue instant(Instant value) {
        return AttributeValue.fromS(value.toString());
    }

    static AttributeValue millis(Instant value) {
        return n(value.toEpochMilli());
    }

    static AttributeValue strings(List<String> values) {
        return AttributeValue.fromL(values.stream().map(AttributeValue::fromS).toList());
    }

    static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null) {
            throw new IllegalStateException("stored item lacks attribute " + name);
        }
        return value.s();
    }

    static String optionalString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    static long number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) {
            throw new IllegalStateException("stored item lacks attribute " + name);
        }
        return Long.parseLong(value.n());
    }

    static Long optionalNumber(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? null : Long.parseLong(value.n());
    }

    static int integer(Map<String, AttributeValue> item, String name) {
        return Math.toIntExact(number(item, name));
    }

    static Integer optionalInteger(Map<String, AttributeValue> item, String name) {
        Long value = optionalNumber(item, name);
        return value == null ? null : Math.toIntExact(value);
    }

    static Instant instant(Map<String, AttributeValue> item, String name) {
        return Instant.parse(string(item, name));
    }

    static Instant optionalInstant(Map<String, AttributeValue> item, String name) {
        String value = optionalString(item, name);
        return value == null ? null : Instant.parse(value);
    }

    static Instant optionalMillis(Map<String, AttributeValue> item, String name) {
        Long value = optionalNumber(item, name);
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    static boolean flag(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    static List<String> stringList(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }

    static boolean present(Map<String, AttributeValue> item, String name) {
        return item.containsKey(name);
    }
}
