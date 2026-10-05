package com.nequi.paymentmock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** ADR-030: state lives only in memory; a new application context starts from the initial state. */
class VolatileStateTest {

    static final String KEY = "volatile-test-key";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void aNewProcessStartsWithoutRulesAndWithTheInitialDefaults() throws Exception {
        try (ConfigurableApplicationContext first = start()) {
            int port = port(first);
            HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(uri(port, "/control/rules"))
                    .header("X-Api-Key", KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"match\":{\"orderId\":\"o\"},\"behaviour\":{\"type\":\"DECLINE\"}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            HttpResponse<String> defaults = HTTP.send(HttpRequest.newBuilder(uri(port, "/control/defaults"))
                    .header("X-Api-Key", KEY).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"defaultOutcome\":\"DECLINED\",\"declinePercentage\":50}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(defaults.statusCode()).isEqualTo(200);
            assertThat(list(port)).isNotEqualTo("[]");
        }
        try (ConfigurableApplicationContext second = start()) {
            assertThat(list(port(second))).isEqualTo("[]");
            assertThat(second.getBean(com.nequi.paymentmock.application.ControlService.class).defaults())
                    .isEqualTo(com.nequi.paymentmock.domain.Defaults.INITIAL);
        }
    }

    static String list(int port) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(port, "/control/rules")).header("X-Api-Key", KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(PaymentMockApplication.class)
                .run("--server.port=0", "--payment-mock.api-key=" + KEY);
    }

    static int port(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
