package com.nequi.ticketing.infrastructure.adapter.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

class SqsClientFactoryTest {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));

    @Test
    @DisplayName("ADR-035 the publication client has no SDK retries, so the 3-attempt policy of the adapter is not stacked")
    void publicationClientDoesNotRetry() {
        try (SqsAsyncClient client = SqsClientFactory.forPublication(
                new SqsConnectionSettings(URI.create("http://localhost:4566"), "us-east-1"), CREDENTIALS)) {
            RetryStrategy strategy = client.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow();
            assertThat(strategy.maxAttempts()).isEqualTo(1);
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://localhost:4566"));
            assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
        }
    }

    @Test
    @DisplayName("ADR-039 the consumption client uses the SDK standard retry mode (receive, delete and visibility have no application retry)")
    void consumptionClientUsesStandardRetry() {
        try (SqsAsyncClient client = SqsClientFactory.forConsumption(new SqsConnectionSettings(null, "eu-west-1"), CREDENTIALS)) {
            RetryStrategy strategy = client.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow();
            assertThat(strategy.maxAttempts()).isEqualTo(3);
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    @DisplayName("Clients with the default credentials chain are built without reading credentials eagerly")
    void defaultCredentials() {
        SqsConnectionSettings settings = new SqsConnectionSettings(URI.create("http://localhost:4566"), "us-east-1");
        try (SqsAsyncClient publication = SqsClientFactory.forPublication(settings);
                SqsAsyncClient consumption = SqsClientFactory.forConsumption(settings)) {
            assertThat(publication.serviceName()).isEqualTo("sqs");
            assertThat(consumption.serviceName()).isEqualTo("sqs");
        }
    }

    @Test
    @DisplayName("The connection requires a region")
    void connectionValidation() {
        assertThatThrownBy(() -> new SqsConnectionSettings(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqsConnectionSettings(null, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsClientFactory.forPublication(null, CREDENTIALS)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqsClientFactory.forConsumption(new SqsConnectionSettings(null, "us-east-1"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
