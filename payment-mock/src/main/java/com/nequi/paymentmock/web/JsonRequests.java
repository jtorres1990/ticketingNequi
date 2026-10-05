package com.nequi.paymentmock.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict validation of JSON request bodies on a parsed tree (PM-SPK-006): Jackson data binding coerces
 * {@code "3"} to 3, {@code 3.7} to 3, numbers to strings and accepts {@code null}, which the contract forbids.
 * Duplicate keys and trailing tokens are rejected while parsing. Every failure is a
 * {@link RequestValidationException} whose message names the field and never contains the input value.
 */
final class JsonRequests {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private JsonRequests() {
    }

    /** Caches the tree deserializer on the calling (startup) thread, see {@link JsonWarmUp}. */
    static void warmUp() {
        STRICT.readTree("{\"warm\":[1,\"up\",true,null,{}]}");
    }

    /** The body must be declared as {@code application/json} (400 instead of 415, PM-IV-005). */
    static void requireJsonContentType(HttpHeaders headers) {
        MediaType contentType;
        try {
            contentType = headers.getContentType();
        } catch (IllegalArgumentException invalid) {
            throw new RequestValidationException("Content-Type must be application/json");
        }
        if (contentType == null || !"application".equals(contentType.getType())
                || !"json".equals(contentType.getSubtype())) {
            throw new RequestValidationException("Content-Type must be application/json");
        }
    }

    static JsonNode parseObject(String body) {
        if (body == null || body.isBlank()) {
            throw new RequestValidationException("Request body is required");
        }
        JsonNode root;
        try {
            root = STRICT.readTree(body);
        } catch (JacksonException malformed) {
            throw new RequestValidationException("Request body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new RequestValidationException("Request body must be a JSON object");
        }
        return root;
    }

    static void onlyFields(JsonNode object, String path, Set<String> allowed) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new RequestValidationException(path + " contains a property that is not allowed");
            }
        }
    }

    static JsonNode requiredObject(JsonNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw new RequestValidationException(path + " is required");
        }
        if (!node.isObject()) {
            throw new RequestValidationException(path + " must be an object");
        }
        return node;
    }

    static String requiredString(JsonNode parent, String field, String path, int maxLength) {
        String value = optionalString(parent, field, path, maxLength);
        if (value == null) {
            throw new RequestValidationException(path + " is required");
        }
        return value;
    }

    static String optionalString(JsonNode parent, String field, String path, int maxLength) {
        JsonNode node = parent.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            throw new RequestValidationException(path + " must be a string");
        }
        String value = node.stringValue();
        if (maxLength >= 0 && codePoints(value) > maxLength) {
            throw new RequestValidationException(path + " exceeds the maximum length of " + maxLength);
        }
        return value;
    }

    static Long optionalInteger(JsonNode parent, String field, String path, long minimum, long maximum) {
        JsonNode node = parent.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new RequestValidationException(path + " must be an integer");
        }
        long value = node.longValue();
        if (value < minimum || value > maximum) {
            throw new RequestValidationException(path + " is out of range");
        }
        return value;
    }

    static <E extends Enum<E>> E optionalEnum(JsonNode parent, String field, String path, Class<E> type) {
        String value = optionalString(parent, field, path, -1);
        if (value == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        throw new RequestValidationException(path + " has a value that is not allowed");
    }

    static List<String> requiredStringArray(JsonNode parent, String field, String path, int minItems, int maxItems,
            int maxItemLength) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw new RequestValidationException(path + " is required");
        }
        if (!node.isArray()) {
            throw new RequestValidationException(path + " must be an array");
        }
        if (node.size() < minItems || node.size() > maxItems) {
            throw new RequestValidationException(path + " must have between " + minItems + " and " + maxItems
                    + " items");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isString()) {
                throw new RequestValidationException(path + " items must be strings");
            }
            if (codePoints(item.stringValue()) > maxItemLength) {
                throw new RequestValidationException(path + " items exceed the maximum length of " + maxItemLength);
            }
            values.add(item.stringValue());
        }
        return values;
    }

    /** JSON Schema {@code maxLength} counts Unicode characters (code points), not UTF-16 units. */
    static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
