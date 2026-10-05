package com.nequi.paymentmock.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.BehaviourType;
import com.nequi.paymentmock.domain.Defaults;
import com.nequi.paymentmock.domain.InvalidConfigurationException;
import com.nequi.paymentmock.domain.MatchField;
import com.nequi.paymentmock.domain.Outcome;
import com.nequi.paymentmock.domain.OutcomeRule;
import com.nequi.paymentmock.domain.ReasonCode;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Own request parsing and response shapes of the control API (no DTO shared with ticketing, ADR-034). */
final class ControlJson {

    static final Set<String> RULE_FIELDS = Set.of("match", "behaviour");
    static final Set<String> MATCH_FIELDS = Set.of("orderId", "ticketId", "customerRef", "eventId");
    static final Set<String> BEHAVIOUR_FIELDS = Set.of("type", "transientFailures", "finalOutcome", "addedLatencyMs",
            "reasonCode");
    static final Set<String> DEFAULTS_FIELDS = Set.of("defaultOutcome", "declinePercentage");

    private ControlJson() {
    }

    /** Parsed {@code OutcomeRuleInput}. */
    record RuleInput(MatchField field, String value, Behaviour behaviour) {
    }

    /** {@code OutcomeRule}: echoed exactly as created (PM-IV-007), with {@code ruleId} (PM-IV-016). */
    @JsonPropertyOrder({"ruleId", "match", "behaviour"})
    record RuleResponse(String ruleId, Map<String, String> match, BehaviourResponse behaviour) {

        static RuleResponse of(OutcomeRule rule) {
            Behaviour behaviour = rule.behaviour();
            return new RuleResponse(rule.ruleId(), Map.of(rule.field().jsonName(), rule.value()),
                    new BehaviourResponse(behaviour.type().name(), behaviour.transientFailures(),
                            behaviour.finalOutcome() == null ? null : behaviour.finalOutcome().name(),
                            behaviour.addedLatencyMs(),
                            behaviour.reasonCode() == null ? null : behaviour.reasonCode().name()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type", "transientFailures", "finalOutcome", "addedLatencyMs", "reasonCode"})
    record BehaviourResponse(String type, Long transientFailures, String finalOutcome, Integer addedLatencyMs,
            String reasonCode) {
    }

    /** {@code Defaults}: always includes {@code declinePercentage} (PM-IV-008). */
    @JsonPropertyOrder({"defaultOutcome", "declinePercentage"})
    record DefaultsResponse(String defaultOutcome, int declinePercentage) {

        static DefaultsResponse of(Defaults defaults) {
            return new DefaultsResponse(defaults.defaultOutcome().name(), defaults.declinePercentage());
        }
    }

    static RuleInput parseRule(String body) {
        JsonNode root = JsonRequests.parseObject(body);
        JsonRequests.onlyFields(root, "body", RULE_FIELDS);
        JsonNode match = JsonRequests.requiredObject(root, "match", "match");
        JsonNode behaviour = JsonRequests.requiredObject(root, "behaviour", "behaviour");

        JsonRequests.onlyFields(match, "match", MATCH_FIELDS);
        if (match.size() != 1) {
            throw new RequestValidationException("match must contain exactly one matcher");
        }
        MatchField field = null;
        String value = null;
        for (MatchField candidate : MatchField.values()) {
            String text = JsonRequests.optionalString(match, candidate.jsonName(), "match." + candidate.jsonName(), -1);
            if (text != null) {
                field = candidate;
                value = text;
            }
        }

        JsonRequests.onlyFields(behaviour, "behaviour", BEHAVIOUR_FIELDS);
        BehaviourType type = JsonRequests.optionalEnum(behaviour, "type", "behaviour.type", BehaviourType.class);
        if (type == null) {
            throw new RequestValidationException("behaviour.type is required");
        }
        Long transientFailures = JsonRequests.optionalInteger(behaviour, "transientFailures",
                "behaviour.transientFailures", 0, Long.MAX_VALUE);
        Outcome finalOutcome = JsonRequests.optionalEnum(behaviour, "finalOutcome", "behaviour.finalOutcome",
                Outcome.class);
        Long addedLatencyMs = JsonRequests.optionalInteger(behaviour, "addedLatencyMs", "behaviour.addedLatencyMs", 0,
                Behaviour.MAX_ADDED_LATENCY_MS);
        ReasonCode reasonCode = JsonRequests.optionalEnum(behaviour, "reasonCode", "behaviour.reasonCode",
                ReasonCode.class);
        try {
            return new RuleInput(field, value, new Behaviour(type, transientFailures, finalOutcome,
                    addedLatencyMs == null ? null : addedLatencyMs.intValue(), reasonCode));
        } catch (InvalidConfigurationException invalid) {
            throw new RequestValidationException(invalid.getMessage());
        }
    }

    static Defaults parseDefaults(String body) {
        JsonNode root = JsonRequests.parseObject(body);
        JsonRequests.onlyFields(root, "body", DEFAULTS_FIELDS);
        Outcome outcome = JsonRequests.optionalEnum(root, "defaultOutcome", "defaultOutcome", Outcome.class);
        if (outcome == null) {
            throw new RequestValidationException("defaultOutcome is required");
        }
        Long percentage = JsonRequests.optionalInteger(root, "declinePercentage", "declinePercentage", 0, 100);
        return new Defaults(outcome, percentage == null ? 0 : percentage.intValue());
    }
}
