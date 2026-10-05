package com.nequi.paymentmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * PM-IV-004: the API key is mandatory (the surefire configuration clears {@code PAYMENT_MOCK_API_KEY}), the port is
 * configurable and nothing secret is logged at startup or while serving requests (ADR-032).
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupConfigurationTest {

    static final String DISTINCTIVE_KEY = "startup-secret-9d41f0b7";

    @Test
    void doesNotStartWithoutApiKey() {
        assertThatThrownBy(() -> start("--server.port=0"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("PAYMENT_MOCK_API_KEY");
    }

    @Test
    void doesNotStartWithBlankApiKey() {
        assertThatThrownBy(() -> start("--server.port=0", "--payment-mock.api-key=   "))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsWithTheKeyAndNeverLogsIt(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = start("--server.port=0",
                "--payment-mock.api-key=" + DISTINCTIVE_KEY)) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> authenticated = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + port + "/control/unknown")).header("X-Api-Key", DISTINCTIVE_KEY).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> rejected = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/control/unknown")).header("X-Api-Key", DISTINCTIVE_KEY + "-bad").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(authenticated.statusCode()).isEqualTo(404);
            assertThat(rejected.statusCode()).isEqualTo(401);
            assertThat(rejected.body()).doesNotContain(DISTINCTIVE_KEY);
        }
        assertThat(output.getAll()).contains("Netty started on port").doesNotContain(DISTINCTIVE_KEY);
    }

    @Test
    void portIs8090UnlessServerPortIsSet() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaymentMockApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run("--payment-mock.api-key=k", "--spring.main.web-application-type=none")) {
            String override = System.getenv("SERVER_PORT");
            assertThat(context.getEnvironment().getProperty("server.port"))
                    .isEqualTo(override == null ? "8090" : override);
        }
    }

    static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(PaymentMockApplication.class).run(args);
    }
}
