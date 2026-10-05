package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Builder of update and condition expressions. Every attribute name and value goes through a placeholder,
 * so reserved words ({@code row}, {@code status}, {@code state}, ...) never reach the expression text.
 */
final class Expression {

    private final Map<String, String> nameToPlaceholder = new HashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, AttributeValue> values = new LinkedHashMap<>();
    private final List<String> sets = new ArrayList<>();
    private final List<String> removes = new ArrayList<>();
    private final List<String> conditions = new ArrayList<>();

    // ------------------------------------------------------------------ placeholders

    String name(String attribute) {
        return nameToPlaceholder.computeIfAbsent(attribute, ignored -> {
            String placeholder = "#n" + names.size();
            names.put(placeholder, attribute);
            return placeholder;
        });
    }

    String value(AttributeValue value) {
        String placeholder = ":v" + values.size();
        values.put(placeholder, value);
        return placeholder;
    }

    // ------------------------------------------------------------------ update clauses

    Expression set(String attribute, AttributeValue value) {
        sets.add(name(attribute) + " = " + value(value));
        return this;
    }

    /** {@code SET} only when the value is present; absent values are not written. */
    Expression setIfPresent(String attribute, AttributeValue value) {
        return value == null ? this : set(attribute, value);
    }

    Expression increment(String attribute, long delta) {
        sets.add(name(attribute) + " = " + name(attribute) + " + " + value(Attributes.n(delta)));
        return this;
    }

    Expression remove(String... attributes) {
        Arrays.stream(attributes).forEach(attribute -> removes.add(name(attribute)));
        return this;
    }

    // ------------------------------------------------------------------ conditions (joined with AND)

    Expression condition(String clause) {
        conditions.add(clause);
        return this;
    }

    String eq(String attribute, AttributeValue value) {
        return name(attribute) + " = " + value(value);
    }

    String compare(String attribute, String operator, AttributeValue value) {
        return name(attribute) + " " + operator + " " + value(value);
    }

    String exists(String attribute) {
        return "attribute_exists(" + name(attribute) + ")";
    }

    String notExists(String attribute) {
        return "attribute_not_exists(" + name(attribute) + ")";
    }

    String in(String attribute, AttributeValue... candidates) {
        return name(attribute) + " IN (" + Arrays.stream(candidates).map(this::value).collect(Collectors.joining(", ")) + ")";
    }

    static String or(String... clauses) {
        return "(" + String.join(" OR ", clauses) + ")";
    }

    // ------------------------------------------------------------------ rendering

    String updateExpression() {
        List<String> parts = new ArrayList<>();
        if (!sets.isEmpty()) {
            parts.add("SET " + String.join(", ", sets));
        }
        if (!removes.isEmpty()) {
            parts.add("REMOVE " + String.join(", ", removes));
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("an update must set or remove at least one attribute");
        }
        return String.join(" ", parts);
    }

    String conditionExpression() {
        return conditions.isEmpty() ? null : String.join(" AND ", conditions);
    }

    /** Placeholder names, or {@code null} when none is used (the service rejects empty maps). */
    Map<String, String> names() {
        return names.isEmpty() ? null : Map.copyOf(names);
    }

    Map<String, AttributeValue> values() {
        return values.isEmpty() ? null : Map.copyOf(values);
    }
}
