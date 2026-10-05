package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSupplier;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

/**
 * Builds the low-level asynchronous DynamoDB client (ADR-039) with the Netty asynchronous HTTP client
 * (IV-001) and the SDK standard retry mode with its default maximum (IV-004, verified in SPK-007). No
 * application retry is stacked on top of it for transient errors (ADR-035).
 */
public final class DynamoDbClientFactory {

    private DynamoDbClientFactory() {
    }

    /**
     * Client with the default credentials chain (environment locally, task role in AWS); credentials are
     * refreshed in the background so that an expiring credential is never reloaded on a request thread.
     */
    public static DynamoDbAsyncClient create(DynamoDbConnectionSettings settings) {
        return create(settings, DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build());
    }

    public static DynamoDbAsyncClient create(DynamoDbConnectionSettings settings, AwsCredentialsProvider credentials) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(credentials, "credentials");
        // The profile file is read once here: by default the SDK re-checks the profile files on the calling
        // thread of every request, which is file-system I/O inside the reactive pipeline (NFR-003).
        ProfileFileSupplier profileFile = ProfileFileSupplier.fixedProfileFile(ProfileFile.defaultProfileFile());
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(Region.of(settings.region()))
                .credentialsProvider(credentials)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .overrideConfiguration(configuration -> configuration
                        .retryStrategy(RetryMode.STANDARD)
                        .defaultProfileFileSupplier(profileFile));
        if (settings.endpointOverride() != null) {
            builder.endpointOverride(settings.endpointOverride());
        }
        return builder.build();
    }
}
