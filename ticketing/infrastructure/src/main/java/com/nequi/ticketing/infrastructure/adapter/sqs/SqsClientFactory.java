package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSupplier;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * Builds the asynchronous SQS clients (ADR-039) with the Netty asynchronous HTTP client (IV-001):
 * <ul>
 *   <li>{@link #forPublication}: without SDK retries, because the publication retry is governed by the
 *       adapter policy of 3 attempts (ADR-035: retries are not stacked; IV-004);</li>
 *   <li>{@link #forConsumption}: SDK standard retry mode for receive, delete and visibility changes, which
 *       have no application retry (the loops continue with a bounded growing wait).</li>
 * </ul>
 * As in the DynamoDB client, the profile file is read once and credentials are refreshed in the background,
 * so that no file-system I/O happens on a request thread (NFR-003).
 */
public final class SqsClientFactory {

    private SqsClientFactory() {
    }

    public static SqsAsyncClient forPublication(SqsConnectionSettings settings) {
        return forPublication(settings, defaultCredentials());
    }

    public static SqsAsyncClient forPublication(SqsConnectionSettings settings, AwsCredentialsProvider credentials) {
        SqsAsyncClientBuilder builder = base(settings, credentials);
        builder.overrideConfiguration(configuration -> configuration
                .retryStrategy(DefaultRetryStrategy.doNotRetry())
                .defaultProfileFileSupplier(fixedProfileFile()));
        return builder.build();
    }

    public static SqsAsyncClient forConsumption(SqsConnectionSettings settings) {
        return forConsumption(settings, defaultCredentials());
    }

    public static SqsAsyncClient forConsumption(SqsConnectionSettings settings, AwsCredentialsProvider credentials) {
        SqsAsyncClientBuilder builder = base(settings, credentials);
        builder.overrideConfiguration(configuration -> configuration
                .retryStrategy(RetryMode.STANDARD)
                .defaultProfileFileSupplier(fixedProfileFile()));
        return builder.build();
    }

    private static SqsAsyncClientBuilder base(SqsConnectionSettings settings, AwsCredentialsProvider credentials) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(credentials, "credentials");
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(settings.region()))
                .credentialsProvider(credentials)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder());
        if (settings.endpointOverride() != null) {
            builder.endpointOverride(settings.endpointOverride());
        }
        return builder;
    }

    private static AwsCredentialsProvider defaultCredentials() {
        return DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
    }

    private static ProfileFileSupplier fixedProfileFile() {
        return ProfileFileSupplier.fixedProfileFile(ProfileFile.defaultProfileFile());
    }
}
