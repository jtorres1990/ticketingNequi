package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiRejection.FieldError;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Syntactic validation of the requests of API-001 to API-006 against the structural rules of
 * {@code ticketing.openapi.v2.yaml} (CMP-001, ADR-035): JSON bodies with {@code additionalProperties: false},
 * required members, JSON types, string formats and lengths ({@code uuid}, {@code date-time},
 * {@code SectionCode}, {@code RowLabel}, {@code TicketId}, {@code Idempotency-Key}) and query parameters.
 * Every invalid member is reported in one {@code VALIDATION_ERROR}. The configurable limits of the domain
 * (capacity, number of sections, rows, seats and complimentary ranges, Tickets per Order and their uniqueness,
 * page sizes) and the business validations are left to the use cases, which apply them in the precedence of
 * BR-031 and ADR-027 (the idempotency lookup of API-001 precedes the business validation).
 */
final class ApiRequests {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern SECTION_CODE = Pattern.compile("[A-Za-z0-9]{1,8}");
    private static final Pattern ROW_LABEL = Pattern.compile("[A-Za-z0-9]{1,6}");
    private static final Pattern TICKET_ID = Pattern.compile("[A-Za-z0-9]{1,8}-[A-Za-z0-9]{1,6}-[0-9]{1,4}");
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]{1,10}");

    private static final int MAXIMUM_TEXT = 200;
    private static final int MAXIMUM_EVENT_CURSOR = 1024;
    private static final int MAXIMUM_AVAILABILITY_CURSOR = 512;

    private static final Set<String> CREATE_EVENT_MEMBERS = Set.of("name", "venue", "startsAt", "capacity", "inventory");
    private static final Set<String> INVENTORY_MEMBERS = Set.of("sections", "complimentary");
    private static final Set<String> SECTION_MEMBERS = Set.of("code", "rows");
    private static final Set<String> ROW_MEMBERS = Set.of("label", "seats");
    private static final Set<String> RANGE_MEMBERS = Set.of("section", "row", "fromSeat", "toSeat");
    private static final Set<String> PURCHASE_MEMBERS = Set.of("eventId", "ticketIds");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ApiRequests() {
    }

    /**
     * Reads the JSON body aggregating at most {@code maximumBytes} in memory; beyond that the read fails with
     * {@code DataBufferLimitException} (413). The {@code Idempotency-Key} header is validated together with the
     * body so that one response reports every invalid input.
     */
    static Mono<JsonNode> readJsonBody(ServerRequest request, int maximumBytes, Errors errors) {
        Optional<MediaType> contentType = request.headers().contentType();
        if (contentType.isEmpty() || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType.get())) {
            errors.add("Content-Type", "must be application/json");
        }
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), maximumBytes)
                .map(ApiRequests::bytes)
                .defaultIfEmpty(new byte[0])
                .map(body -> {
                    if (body.length == 0) {
                        errors.add("body", "is required");
                        throw errors.rejection();
                    }
                    try {
                        return JSON.readTree(body);
                    } catch (JacksonException malformed) {
                        errors.add("body", "must be a valid JSON document");
                        throw errors.rejection();
                    }
                });
    }

    static String idempotencyKey(ServerRequest request, Errors errors) {
        List<String> values = request.headers().header(IDEMPOTENCY_KEY);
        if (values.isEmpty()) {
            errors.add(IDEMPOTENCY_KEY, "is required");
            return null;
        }
        String key = values.getFirst();
        if (values.size() > 1 || !IDEMPOTENCY_KEY_FORMAT.matcher(key).matches()) {
            errors.add(IDEMPOTENCY_KEY, "must be a single value of 16 to 64 characters [A-Za-z0-9_-]");
            return null;
        }
        return key;
    }

    /** API-001 {@code CreateEventRequest}. */
    static CreateEventInput createEvent(JsonNode body, Errors errors) {
        if (!object(body, "body", CREATE_EVENT_MEMBERS, errors)) {
            throw errors.rejection();
        }
        String name = text(body, "name", "name", errors);
        String venue = text(body, "venue", "venue", errors);
        Instant startsAt = dateTime(body, "startsAt", errors);
        Integer capacity = integer(body.get("capacity"), "capacity", errors);
        InventoryDefinition inventory = inventory(body.get("inventory"), errors);
        if (errors.any()) {
            throw errors.rejection();
        }
        return new CreateEventInput(name, venue, startsAt, capacity, inventory);
    }

    /** API-004 {@code StartPurchaseRequest}. */
    static PurchaseInput purchase(JsonNode body, Errors errors) {
        if (!object(body, "body", PURCHASE_MEMBERS, errors)) {
            throw errors.rejection();
        }
        String eventId = null;
        JsonNode eventNode = body.get("eventId");
        if (eventNode == null) {
            errors.add("eventId", "is required");
        } else if (!eventNode.isString() || !UUID.matcher(eventNode.stringValue()).matches()) {
            errors.add("eventId", "must be a UUID");
        } else {
            eventId = eventNode.stringValue();
        }
        List<String> ticketIds = new ArrayList<>();
        JsonNode ticketsNode = body.get("ticketIds");
        if (ticketsNode == null) {
            errors.add("ticketIds", "is required");
        } else if (!ticketsNode.isArray()) {
            errors.add("ticketIds", "must be an array");
        } else {
            for (int index = 0; index < ticketsNode.size(); index++) {
                JsonNode ticket = ticketsNode.get(index);
                if (!ticket.isString() || !TICKET_ID.matcher(ticket.stringValue()).matches()) {
                    errors.add("ticketIds[" + index + "]", "must be a ticket identifier <section>-<row>-<seat>");
                } else {
                    ticketIds.add(ticket.stringValue());
                }
            }
        }
        if (errors.any()) {
            throw errors.rejection();
        }
        return new PurchaseInput(eventId, List.copyOf(ticketIds));
    }

    static Integer queryInteger(ServerRequest request, String name, Errors errors) {
        Optional<String> value = request.queryParam(name);
        if (value.isEmpty()) {
            return null;
        }
        if (!INTEGER.matcher(value.get()).matches()) {
            errors.add(name, "must be an integer");
            return null;
        }
        long parsed = Long.parseLong(value.get());
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            errors.add(name, "must be an integer");
            return null;
        }
        return (int) parsed;
    }

    static String eventCursor(ServerRequest request, Errors errors) {
        return cursor(request, MAXIMUM_EVENT_CURSOR, errors);
    }

    static String availabilityCursor(ServerRequest request, Errors errors) {
        return cursor(request, MAXIMUM_AVAILABILITY_CURSOR, errors);
    }

    static String section(ServerRequest request, Errors errors) {
        Optional<String> value = request.queryParam("section");
        if (value.isEmpty()) {
            return null;
        }
        if (!SECTION_CODE.matcher(value.get()).matches()) {
            errors.add("section", "must be a section code of 1 to 8 characters [A-Za-z0-9]");
            return null;
        }
        return value.get();
    }

    static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }

    private static String cursor(ServerRequest request, int maximumLength, Errors errors) {
        Optional<String> value = request.queryParam("cursor");
        if (value.isEmpty()) {
            return null;
        }
        if (value.get().isEmpty() || value.get().length() > maximumLength) {
            errors.add("cursor", "cursor is invalid");
            return null;
        }
        return value.get();
    }

    private static InventoryDefinition inventory(JsonNode node, Errors errors) {
        if (node == null) {
            errors.add("inventory", "is required");
            return null;
        }
        if (!object(node, "inventory", INVENTORY_MEMBERS, errors)) {
            return null;
        }
        List<Section> sections = sections(node.get("sections"), errors);
        List<ComplimentaryRange> ranges = complimentary(node.get("complimentary"), errors);
        if (sections == null || ranges == null) {
            return null;
        }
        return new InventoryDefinition(sections, ranges);
    }

    private static List<Section> sections(JsonNode node, Errors errors) {
        String field = "inventory.sections";
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isArray()) {
            errors.add(field, "must be an array");
            return null;
        }
        if (node.isEmpty()) {
            errors.add(field, "must contain at least one section");
            return null;
        }
        int before = errors.count();
        List<Section> sections = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            String path = field + "[" + index + "]";
            JsonNode section = node.get(index);
            if (!object(section, path, SECTION_MEMBERS, errors)) {
                continue;
            }
            String code = pattern(section, "code", path + ".code", SECTION_CODE, "must be a section code of 1 to 8 characters [A-Za-z0-9]", errors);
            List<Row> rows = rows(section.get("rows"), path + ".rows", errors);
            if (code != null && rows != null) {
                sections.add(new Section(code, rows));
            }
        }
        return errors.count() == before ? sections : null;
    }

    private static List<Row> rows(JsonNode node, String field, Errors errors) {
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isArray()) {
            errors.add(field, "must be an array");
            return null;
        }
        if (node.isEmpty()) {
            errors.add(field, "must contain at least one row");
            return null;
        }
        int before = errors.count();
        List<Row> rows = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            String path = field + "[" + index + "]";
            JsonNode row = node.get(index);
            if (!object(row, path, ROW_MEMBERS, errors)) {
                continue;
            }
            String label = pattern(row, "label", path + ".label", ROW_LABEL, "must be a row label of 1 to 6 characters [A-Za-z0-9]", errors);
            Integer seats = integer(row.get("seats"), path + ".seats", errors);
            if (label != null && seats != null) {
                rows.add(new Row(label, seats));
            }
        }
        return errors.count() == before ? rows : null;
    }

    private static List<ComplimentaryRange> complimentary(JsonNode node, Errors errors) {
        String field = "inventory.complimentary";
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(field, "must be an array");
            return null;
        }
        int before = errors.count();
        List<ComplimentaryRange> ranges = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            String path = field + "[" + index + "]";
            JsonNode range = node.get(index);
            if (!object(range, path, RANGE_MEMBERS, errors)) {
                continue;
            }
            String section = pattern(range, "section", path + ".section", SECTION_CODE, "must be a section code of 1 to 8 characters [A-Za-z0-9]", errors);
            String row = pattern(range, "row", path + ".row", ROW_LABEL, "must be a row label of 1 to 6 characters [A-Za-z0-9]", errors);
            Integer fromSeat = integer(range.get("fromSeat"), path + ".fromSeat", errors);
            Integer toSeat = integer(range.get("toSeat"), path + ".toSeat", errors);
            if (section != null && row != null && fromSeat != null && toSeat != null) {
                ranges.add(new ComplimentaryRange(section, row, fromSeat, toSeat));
            }
        }
        return errors.count() == before ? ranges : null;
    }

    /**
     * True when {@code node} is a JSON object; every member outside {@code members} is reported
     * ({@code additionalProperties: false}) and the known members are still validated.
     */
    private static boolean object(JsonNode node, String field, Set<String> members, Errors errors) {
        if (node == null || !node.isObject()) {
            errors.add(field, "must be a JSON object");
            return false;
        }
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            if (!members.contains(member.getKey())) {
                errors.add(qualified(field, member.getKey()), "is not allowed");
            }
        }
        return true;
    }

    private static String text(JsonNode body, String member, String field, Errors errors) {
        JsonNode node = body.get(member);
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isString()) {
            errors.add(field, "must be a string");
            return null;
        }
        String value = node.stringValue();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > MAXIMUM_TEXT) {
            errors.add(field, "must have 1 to " + MAXIMUM_TEXT + " characters");
            return null;
        }
        return value;
    }

    private static String pattern(JsonNode parent, String member, String field, Pattern format, String reason, Errors errors) {
        JsonNode node = parent.get(member);
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isString() || !format.matcher(node.stringValue()).matches()) {
            errors.add(field, reason);
            return null;
        }
        return node.stringValue();
    }

    private static Instant dateTime(JsonNode body, String field, Errors errors) {
        JsonNode node = body.get(field);
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isString()) {
            errors.add(field, "must be an RFC 3339 date-time with offset");
            return null;
        }
        try {
            return OffsetDateTime.parse(node.stringValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException invalid) {
            errors.add(field, "must be an RFC 3339 date-time with offset");
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field, Errors errors) {
        if (node == null) {
            errors.add(field, "is required");
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(field, "must be an integer");
            return null;
        }
        return node.intValue();
    }

    private static String qualified(String parent, String member) {
        return "body".equals(parent) ? member : parent + "." + member;
    }

    private static byte[] bytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /** Collector of the field errors of one request. */
    static final class Errors {

        private final List<FieldError> errors = new ArrayList<>();

        void add(String field, String reason) {
            errors.add(new FieldError(field, reason));
        }

        boolean any() {
            return !errors.isEmpty();
        }

        int count() {
            return errors.size();
        }

        ApiRejection rejection() {
            return ApiRejection.invalid(errors);
        }

        void throwIfAny() {
            if (any()) {
                throw rejection();
            }
        }
    }

    record CreateEventInput(String name, String venue, Instant startsAt, int capacity, InventoryDefinition inventory) {
    }

    record PurchaseInput(String eventId, List<String> ticketIds) {
    }
}
