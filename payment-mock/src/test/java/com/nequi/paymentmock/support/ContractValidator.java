package com.nequi.paymentmock.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;

/**
 * Validates recorded requests and responses against the copy of {@code payment-mock.openapi.v1.yaml} (G3).
 *
 * <p>Known limitation of the validator (PM-SPK-003): it does not evaluate the {@code apiKey} security scheme, so
 * authentication is asserted explicitly by the security tests.
 *
 * <p>PM-IV-016 (approved tolerance): the schema {@code OutcomeRule} is {@code allOf(OutcomeRuleInput, {ruleId})}
 * with {@code additionalProperties: false} in the first branch, so no instance with {@code ruleId} can satisfy it
 * when the validator evaluates the branches separately. Only for the responses of API-103 and API-104, exactly that
 * {@code allOf} violation is tolerated: an {@code allOf} failure whose only nested messages are the two
 * {@code additionalProperties} complaints about {@code ["ruleId"]} and {@code ["behaviour","match"]}. Every
 * tolerated response is additionally validated with combinators resolved, which must report no error. Any other
 * violation (extra property, missing {@code ruleId}, wrong enumeration...) is still reported.
 */
public final class ContractValidator {

    static final String RULES_PATH = "/control/rules";
    static final String ALL_OF = "validation.response.body.schema.allOf";
    static final String ADDITIONAL_PROPERTIES = "validation.response.body.schema.additionalProperties";
    static final Set<String> OUTCOME_RULE_DEFECT = Set.of(
            "Object instance has properties which are not allowed by the schema: [\"ruleId\"]",
            "Object instance has properties which are not allowed by the schema: [\"behaviour\",\"match\"]");

    private static final OpenApiInteractionValidator AS_PUBLISHED =
            OpenApiInteractionValidator.createForInlineApiSpecification(Contract.text()).build();
    private static final OpenApiInteractionValidator COMBINATORS_RESOLVED =
            OpenApiInteractionValidator.createForInlineApiSpecification(Contract.text())
                    .withResolveCombinators(true).build();

    private ContractValidator() {
    }

    /** Recorded HTTP interaction, independent of the client used. */
    public record Interaction(String method, String rawPath, HttpHeaders requestHeaders, byte[] requestBody,
            int status, HttpHeaders responseHeaders, byte[] responseBody) {
    }

    /** All violations of the interaction (request and response), after the PM-IV-016 tolerance. */
    public static List<String> violations(Interaction interaction) {
        List<String> violations = new ArrayList<>(requestViolations(interaction));
        violations.addAll(responseViolations(interaction));
        return violations;
    }

    /** Violations of the request alone (expected in negative tests). */
    public static List<String> requestViolations(Interaction interaction) {
        return AS_PUBLISHED.validateRequest(request(interaction)).getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .map(ContractValidator::render)
                .toList();
    }

    /** Violations of the response alone, after the PM-IV-016 tolerance. */
    public static List<String> responseViolations(Interaction interaction) {
        Request.Method method = Request.Method.valueOf(interaction.method());
        ValidationReport report = AS_PUBLISHED.validateResponse(interaction.rawPath(), method, response(interaction));
        List<String> violations = new ArrayList<>();
        boolean tolerated = false;
        for (ValidationReport.Message message : report.getMessages()) {
            if (message.getLevel() != ValidationReport.Level.ERROR) {
                continue;
            }
            if (isOutcomeRuleDefect(method, interaction.rawPath(), message)) {
                tolerated = true;
                continue;
            }
            violations.add(render(message));
        }
        if (tolerated) {
            ValidationReport resolved = COMBINATORS_RESOLVED.validateResponse(interaction.rawPath(), method,
                    response(interaction));
            resolved.getMessages().stream()
                    .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                    .map(message -> "[combinators resolved] " + render(message))
                    .forEach(violations::add);
        }
        return violations;
    }

    /** Validation with the published contract and no tolerance at all (used to prove the defect and the limits). */
    public static List<String> violationsWithoutTolerance(Interaction interaction) {
        return AS_PUBLISHED.validate(request(interaction), response(interaction)).getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .map(ContractValidator::render)
                .toList();
    }

    static boolean isOutcomeRuleDefect(Request.Method method, String path, ValidationReport.Message message) {
        if (!RULES_PATH.equals(path) || (method != Request.Method.GET && method != Request.Method.POST)) {
            return false;
        }
        if (!ALL_OF.equals(message.getKey())) {
            return false;
        }
        List<ValidationReport.Message> nested = message.getNestedMessages();
        if (nested.size() != OUTCOME_RULE_DEFECT.size()) {
            return false;
        }
        Set<String> texts = nested.stream()
                .filter(inner -> ADDITIONAL_PROPERTIES.equals(inner.getKey()))
                .map(inner -> stripPathPrefix(inner.getMessage()))
                .collect(Collectors.toSet());
        return texts.equals(OUTCOME_RULE_DEFECT);
    }

    private static String stripPathPrefix(String text) {
        return text.startsWith("[Path '") ? text.substring(text.indexOf("] ") + 2) : text;
    }

    private static String render(ValidationReport.Message message) {
        StringBuilder text = new StringBuilder(message.getKey()).append(": ").append(message.getMessage());
        for (ValidationReport.Message nested : message.getNestedMessages()) {
            text.append(" | ").append(render(nested));
        }
        return text.toString();
    }

    private static Request request(Interaction interaction) {
        SimpleRequest.Builder builder = new SimpleRequest.Builder(interaction.method(), interaction.rawPath());
        interaction.requestHeaders().forEach(builder::withHeader);
        if (interaction.requestBody() != null && interaction.requestBody().length > 0) {
            builder.withBody(new String(interaction.requestBody(), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static SimpleResponse response(Interaction interaction) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(interaction.status());
        interaction.responseHeaders().forEach(builder::withHeader);
        if (interaction.responseBody() != null && interaction.responseBody().length > 0) {
            builder.withBody(new String(interaction.responseBody(), StandardCharsets.UTF_8));
        }
        return builder.build();
    }
}
