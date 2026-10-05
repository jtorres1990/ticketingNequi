package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Integration test support: one LocalStack container per test JVM with the image pinned in
 * {@code implementation/ticketing.local-environment.v1.md} (removed by Testcontainers' reaper), and the
 * queue fixture of {@code ticketing.messaging.v2.md} §1. The application never creates queues; only this
 * fixture does, mirroring what {@code infra-init} creates locally (ADR-036).
 */
public final class LocalStackSqsSupport {

    public static final DockerImageName IMAGE = DockerImageName.parse("localstack/localstack:4.14.0");

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(IMAGE)
            .withExposedPorts(4566)
            .withEnv("SERVICES", "sqs")
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static SqsAsyncClient admin;

    private LocalStackSqsSupport() {
    }

    public static synchronized GenericContainer<?> container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static SqsConnectionSettings connection() {
        GenericContainer<?> running = container();
        return new SqsConnectionSettings(
                URI.create("http://" + running.getHost() + ":" + running.getMappedPort(4566)), "us-east-1");
    }

    /** LocalStack accepts any credentials; these are placeholders, not secrets. */
    public static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    }

    /** Client for the fixture and for test assertions (consumption profile of the production factory). */
    public static synchronized SqsAsyncClient admin() {
        if (admin == null) {
            admin = SqsClientFactory.forConsumption(connection(), localCredentials());
        }
        return admin;
    }

    /** The four queues of messaging §1 with their attributes, under a unique prefix. */
    public static Queues createQueues() {
        return createQueues(Map.of(), Map.of());
    }

    /**
     * Same fixture with optional attribute overrides for the Orders and provisioning main queues (only to
     * shorten waits in tests that observe redelivery; the defaults are the literal values of messaging §1).
     */
    public static Queues createQueues(Map<QueueAttributeName, String> ordersOverrides,
            Map<QueueAttributeName, String> provisioningOverrides) {
        String prefix = "it-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String ordersDlq = create(prefix + "ticketing-orders-dlq", Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "60",
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600",
                QueueAttributeName.DELAY_SECONDS, "0"));
        String provisioningDlq = create(prefix + "ticketing-event-provisioning-dlq", Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "120",
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600",
                QueueAttributeName.DELAY_SECONDS, "0"));
        Map<QueueAttributeName, String> orders = new HashMap<>(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "60",
                QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20",
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "3600",
                QueueAttributeName.DELAY_SECONDS, "0",
                QueueAttributeName.REDRIVE_POLICY, redrive(arn(ordersDlq))));
        orders.putAll(ordersOverrides);
        Map<QueueAttributeName, String> provisioning = new HashMap<>(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "120",
                QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20",
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "86400",
                QueueAttributeName.DELAY_SECONDS, "0",
                QueueAttributeName.REDRIVE_POLICY, redrive(arn(provisioningDlq))));
        provisioning.putAll(provisioningOverrides);
        return new Queues(
                create(prefix + "ticketing-orders", orders), ordersDlq,
                create(prefix + "ticketing-event-provisioning", provisioning), provisioningDlq);
    }

    public static Map<String, String> attributes(String queueUrl) {
        return admin().getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.ALL))
                .join().attributesAsStrings();
    }

    private static String create(String name, Map<QueueAttributeName, String> attributes) {
        return admin().createQueue(b -> b.queueName(name).attributes(attributes)).join().queueUrl();
    }

    private static String arn(String queueUrl) {
        return admin().getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .join().attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static String redrive(String dlqArn) {
        return "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
    }

    /** Queue URLs of one fixture instance. */
    public record Queues(String orders, String ordersDlq, String provisioning, String provisioningDlq) {
    }
}
