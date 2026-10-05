package com.nequi.paymentmock.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web-layer tests against the application started on a random port. Every interaction sent through
 * {@link #send} is validated against the OpenAPI copy unless the test explicitly asks for the raw result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payment-mock.api-key=" + WebTestSupport.API_KEY)
public abstract class WebTestSupport {

    public static final String API_KEY = "test-api-key-5b1e7c2a";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    protected int port;

    protected WebTestClient client;

    @BeforeEach
    void createClientAndResetMock() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        // Every scenario starts from the initial state, as QA does (AV-004).
        assertThat(send(HttpMethod.POST, "/control/reset", null).status()).isEqualTo(204);
    }

    /** HTTP response of one interaction plus its contract violations. */
    public record Response(int status, HttpHeaders headers, byte[] rawBody, List<String> requestViolations,
            List<String> responseViolations) {

        public List<String> violations() {
            List<String> all = new java.util.ArrayList<>(requestViolations);
            all.addAll(responseViolations);
            return all;
        }

        public String body() {
            return rawBody == null ? "" : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return JSON.readTree(body());
        }

        public Response assertConforms() {
            assertThat(violations()).as("contract violations").isEmpty();
            return this;
        }

        /** For negative tests: the request is invalid on purpose, the response must still conform. */
        public Response assertResponseConforms() {
            assertThat(responseViolations).as("response contract violations").isEmpty();
            return this;
        }
    }

    /** Sends a request with the valid API key and a JSON body when present; asserts contract conformance. */
    protected Response send(HttpMethod method, String path, String jsonBody) {
        return send(method, path, jsonBody, headers -> headers.set("X-Api-Key", API_KEY)).assertConforms();
    }

    /** Sends a request with exactly the given headers (plus JSON content type when a body is present). */
    protected Response send(HttpMethod method, String path, String jsonBody, Consumer<HttpHeaders> headers) {
        WebTestClient.RequestBodySpec spec = client.method(method).uri(path).headers(headers);
        WebTestClient.RequestHeadersSpec<?> request = jsonBody == null
                ? spec
                : spec.headers(h -> {
                    if (h.getFirst(HttpHeaders.CONTENT_TYPE) == null) {
                        h.setContentType(MediaType.APPLICATION_JSON);
                    }
                }).bodyValue(jsonBody);
        EntityExchangeResult<byte[]> result = request.exchange().expectBody().returnResult();
        return toResponse(result);
    }

    protected static Response toResponse(EntityExchangeResult<byte[]> result) {
        ContractValidator.Interaction interaction = interaction(result);
        return new Response(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody(),
                ContractValidator.requestViolations(interaction), ContractValidator.responseViolations(interaction));
    }

    protected static ContractValidator.Interaction interaction(EntityExchangeResult<byte[]> result) {
        return new ContractValidator.Interaction(result.getMethod().name(), result.getUrl().getRawPath(),
                result.getRequestHeaders(), result.getRequestBodyContent(), result.getStatus().value(),
                result.getResponseHeaders(), result.getResponseBody());
    }

    protected Response get(String path) {
        return send(HttpMethod.GET, path, null);
    }

    protected Response post(String path, String body) {
        return send(HttpMethod.POST, path, body);
    }

    protected Response put(String path, String body) {
        return send(HttpMethod.PUT, path, body);
    }

    protected Response delete(String path) {
        return send(HttpMethod.DELETE, path, null);
    }

    /** Single quotes to double quotes, to keep JSON literals readable in tests. */
    public static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }
}
