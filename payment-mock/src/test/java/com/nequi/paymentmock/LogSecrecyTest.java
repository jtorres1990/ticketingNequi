package com.nequi.paymentmock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * G6 (ADR-032): a full scenario (control, authorization, conflict, invalid input, cancellation, inspection, unknown
 * route, wrong key) never writes the API key or the customer reference to the logs or to any response.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogSecrecyTest {

    static final String KEY = "log-secret-key-3c8a1f";
    static final String CUSTOMER = "customer-sub-7e2d9b41";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void neitherApiKeyNorCustomerReferenceAppearInLogsOrResponses(CapturedOutput output) throws Exception {
        StringBuilder responses = new StringBuilder();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaymentMockApplication.class)
                .run("--server.port=0", "--payment-mock.api-key=" + KEY)) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            String base = "http://127.0.0.1:" + port;
            String body = "{\"paymentAttemptId\":\"s-1\",\"orderId\":\"8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11\","
                    + "\"eventId\":\"3f1c0b7e-2a4d-4e5f-9a8b-7c6d5e4f3a2b\",\"customerRef\":\"" + CUSTOMER
                    + "\",\"ticketIds\":[\"T1\"]}";
            responses.append(call(base + "/control/rules", "POST", KEY,
                    "{\"match\":{\"customerRef\":\"" + CUSTOMER + "\"},\"behaviour\":{\"type\":\"DECLINE\"}}"));
            responses.append(call(base + "/payments", "POST", KEY, body));
            responses.append(call(base + "/payments", "POST", KEY, body.replace("\"s-1\"", "\"s-1\"")
                    .replace("T1", "T2")));
            responses.append(call(base + "/payments", "POST", KEY,
                    body.replace(CUSTOMER, CUSTOMER.repeat(10))));
            responses.append(call(base + "/payments", "POST", KEY, body.replace("}", ",\"x\":\"" + CUSTOMER + "\"}")));
            responses.append(call(base + "/payments/s-1/cancellation", "POST", KEY, null));
            responses.append(call(base + "/control/authorizations/s-1", "GET", KEY, null));
            responses.append(call(base + "/control/cancellations/s-1", "GET", KEY, null));
            responses.append(call(base + "/nothing", "GET", KEY, null));
            responses.append(call(base + "/payments", "POST", KEY + "-wrong", body));
        }
        // The rule list echoes the configured matcher by design (control API); every other response must not
        // contain the customer reference.
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain(CUSTOMER);
        assertThat(responses.toString()).doesNotContain(KEY);
        assertThat(responses.toString().replaceFirst("\\{\"ruleId\"[^}]*}[^}]*}}", "")).doesNotContain(CUSTOMER);
    }

    static String call(String url, String method, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("X-Api-Key", key);
        if (url.endsWith("/payments")) {
            request.header("Idempotency-Key", "s-1");
        }
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body() + "\n";
    }
}
