package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.ExchangeResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Validation of the HTTP interactions of the web layer tests against the versioned copy of
 * {@code ticketing.openapi.v2.yaml} (IV-003 (a); validator of IV-001). Every response is validated (status
 * declared for the operation, headers, {@code application/json} or {@code application/problem+json} body
 * schema); requests are validated too when the test sends a request that the contract accepts.
 */
final class OpenApiContract {

    static final String CONTRACT = "/contracts/ticketing.openapi.v2.yaml";

    private static final OpenApiInteractionValidator VALIDATOR =
            OpenApiInteractionValidator.createForInlineApiSpecification(content())
                    // allOf (ValidationProblem, PurchaseConflictProblem, UnknownTicketsProblem) is merged before
                    // validation; otherwise the validator checks each part as a closed object (README FAQ).
                    .withResolveCombinators(true)
                    .build();

    private OpenApiContract() {
    }

    static String content() {
        try (InputStream in = Objects.requireNonNull(OpenApiContract.class.getResourceAsStream(CONTRACT), CONTRACT)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Violations of the response, and of the request when {@code includeRequest}. */
    static List<String> violations(ExchangeResult result, byte[] responseBody, boolean includeRequest) {
        URI url = result.getUrl();
        Request.Method method = Request.Method.valueOf(result.getMethod().name());
        SimpleResponse.Builder response = SimpleResponse.Builder.status(result.getStatus().value());
        HttpHeaders responseHeaders = result.getResponseHeaders();
        responseHeaders.forEach((name, values) -> response.withHeader(name, values));
        if (responseBody != null && responseBody.length > 0) {
            response.withBody(new String(responseBody, StandardCharsets.UTF_8));
        }
        ValidationReport report;
        if (includeRequest) {
            SimpleRequest.Builder request = new SimpleRequest.Builder(method, url.getRawPath());
            UriComponentsBuilder.fromUri(url).build().getQueryParams()
                    .forEach((name, values) -> request.withQueryParam(name, values));
            result.getRequestHeaders().forEach((name, values) -> request.withHeader(name, values));
            byte[] requestBody = result.getRequestBodyContent();
            if (requestBody != null && requestBody.length > 0) {
                request.withBody(new String(requestBody, StandardCharsets.UTF_8));
            }
            report = VALIDATOR.validate(request.build(), response.build());
        } else {
            report = VALIDATOR.validateResponse(url.getRawPath(), method, response.build());
        }
        return report.getMessages().stream()
                .map(message -> message.getKey() + ": " + message.getMessage())
                .toList();
    }
}
