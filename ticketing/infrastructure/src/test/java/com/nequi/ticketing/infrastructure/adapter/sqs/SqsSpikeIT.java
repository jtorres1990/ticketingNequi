package com.nequi.ticketing.infrastructure.adapter.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Spikes SPK-016 to SPK-018 of INC-006 ({@code ticketing.architecture.v2.md} §13 items 14, 16, 17),
 * executed against the pinned LocalStack image (and, for the service errors LocalStack Community cannot
 * produce, against a local HTTP stub speaking the SQS JSON protocol). The results are recorded in the
 * INC-006 report; the tests stay in the build as regression evidence of the emulator and SDK behaviour the
 * adapters rely on.
 */
class SqsSpikeIT {

    private static SqsAsyncClient admin;
    private static SqsAsyncClient publication;
    private static LocalStackSqsSupport.Queues queues;

    @BeforeAll
    static void start() {
        admin = LocalStackSqsSupport.admin();
        publication = SqsClientFactory.forPublication(LocalStackSqsSupport.connection(),
                LocalStackSqsSupport.localCredentials());
        queues = LocalStackSqsSupport.createQueues();
    }

    // ------------------------------------------------------------------ SPK-016

    @Test
    @DisplayName("SPK-016 the queue attributes of messaging v2 §1 are accepted by LocalStack 4.14.0 and observable from the SDK")
    void queueAttributesAreObservable() {
        Map<String, String> orders = LocalStackSqsSupport.attributes(queues.orders());
        Map<String, String> ordersDlq = LocalStackSqsSupport.attributes(queues.ordersDlq());
        Map<String, String> provisioning = LocalStackSqsSupport.attributes(queues.provisioning());
        Map<String, String> provisioningDlq = LocalStackSqsSupport.attributes(queues.provisioningDlq());
        System.out.println("SPK-016 orders=" + orders);
        System.out.println("SPK-016 provisioning=" + provisioning);

        assertThat(orders).containsEntry("VisibilityTimeout", "60").containsEntry("ReceiveMessageWaitTimeSeconds", "20")
                .containsEntry("MessageRetentionPeriod", "3600").containsEntry("DelaySeconds", "0");
        assertThat(orders.get("RedrivePolicy")).contains("maxReceiveCount").contains("5")
                .contains(ordersDlq.get("QueueArn"));
        assertThat(ordersDlq).containsEntry("VisibilityTimeout", "60").containsEntry("MessageRetentionPeriod", "1209600");
        assertThat(provisioning).containsEntry("VisibilityTimeout", "120")
                .containsEntry("ReceiveMessageWaitTimeSeconds", "20")
                .containsEntry("MessageRetentionPeriod", "86400").containsEntry("DelaySeconds", "0");
        assertThat(provisioning.get("RedrivePolicy")).contains("5").contains(provisioningDlq.get("QueueArn"));
        assertThat(provisioningDlq).containsEntry("VisibilityTimeout", "120")
                .containsEntry("MessageRetentionPeriod", "1209600");
    }

    @Test
    @DisplayName("SPK-016 receive returns at most 10 messages, accepts a 20 s wait and rejects 11 messages or a 21 s wait")
    void receiveLimits() {
        LocalStackSqsSupport.Queues own = LocalStackSqsSupport.createQueues();
        for (int index = 0; index < 12; index++) {
            int number = index;
            admin.sendMessage(b -> b.queueUrl(own.orders()).messageBody("m" + number)).join();
        }
        ReceiveMessageResponse ten = admin.receiveMessage(b -> b.queueUrl(own.orders()).maxNumberOfMessages(10)
                .waitTimeSeconds(20)).join();
        System.out.println("SPK-016 receive(max 10, wait 20) returned " + ten.messages().size() + " messages");
        assertThat(ten.messages()).hasSizeBetween(1, 10);

        Throwable eleven = failure(admin.receiveMessage(b -> b.queueUrl(own.orders()).maxNumberOfMessages(11)));
        Throwable longWait = failure(admin.receiveMessage(b -> b.queueUrl(own.orders()).waitTimeSeconds(21)));
        System.out.println("SPK-016 receive(max 11) -> " + describe(eleven) + "; receive(wait 21) -> " + describe(longWait));
        assertThat(eleven).isInstanceOf(AwsServiceException.class);
        assertThat(longWait).isInstanceOf(AwsServiceException.class);
    }

    @Test
    @DisplayName("SPK-016 long polling returns as soon as a message arrives within the 20 s wait")
    void longPollingReturnsEarly() {
        LocalStackSqsSupport.Queues own = LocalStackSqsSupport.createQueues();
        long started = System.nanoTime();
        CompletableFuture<ReceiveMessageResponse> receive = admin.receiveMessage(b -> b.queueUrl(own.orders())
                .waitTimeSeconds(20).maxNumberOfMessages(10));
        Mono.delay(Duration.ofSeconds(2))
                .then(Mono.fromFuture(() -> admin.sendMessage(b -> b.queueUrl(own.orders()).messageBody("late"))))
                .subscribe();
        ReceiveMessageResponse response = receive.join();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("SPK-016 long poll returned " + response.messages().size() + " message(s) after "
                + elapsedMs + " ms");
        assertThat(response.messages()).hasSize(1);
        assertThat(elapsedMs).isLessThan(15_000);
    }

    @Test
    @DisplayName("SPK-016 ApproximateReceiveCount increments per reception and visibility changes to 0, 120 s and 43200 s are accepted")
    void receiveCountAndVisibility() {
        LocalStackSqsSupport.Queues own = LocalStackSqsSupport.createQueues();
        admin.sendMessage(b -> b.queueUrl(own.provisioning()).messageBody("count")).join();
        List<Integer> counts = new ArrayList<>();
        for (int reception = 0; reception < 3; reception++) {
            Message message = receiveOne(own.provisioning());
            counts.add(Integer.parseInt(message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)));
            changeVisibility(own.provisioning(), message, 120);
            changeVisibility(own.provisioning(), message, 43_200);
            changeVisibility(own.provisioning(), message, 0);
        }
        System.out.println("SPK-016 ApproximateReceiveCount over three receptions: " + counts);
        assertThat(counts).containsExactly(1, 2, 3);

        // Out-of-range validation is recorded, not asserted: LocalStack 4.14.0 is more permissive than SQS here,
        // and no approved value is near the limit (ADR-029 uses 60 s and 120 s).
        Message message = receiveOne(own.provisioning());
        System.out.println("SPK-016 visibility 43201 s -> " + outcome(admin.changeMessageVisibility(b -> b
                .queueUrl(own.provisioning()).receiptHandle(message.receiptHandle()).visibilityTimeout(43_201))));
    }

    @Test
    @DisplayName("SPK-016 retention of 1 hour, 1 day and 14 days accepted; out-of-range values recorded")
    void retentionLimits() {
        String base = "spk016-retention-" + System.nanoTime();
        for (String seconds : List.of("3600", "86400", "1209600")) {
            String url = admin.createQueue(b -> b.queueName(base + "-" + seconds)
                    .attributes(Map.of(QueueAttributeName.MESSAGE_RETENTION_PERIOD, seconds))).join().queueUrl();
            assertThat(LocalStackSqsSupport.attributes(url)).containsEntry("MessageRetentionPeriod", seconds);
        }
        // Out-of-range validation is recorded, not asserted (LocalStack 4.14.0 may be more permissive than SQS).
        System.out.println("SPK-016 retention 59 s -> " + outcome(admin.createQueue(b -> b.queueName(base + "-short")
                .attributes(Map.of(QueueAttributeName.MESSAGE_RETENTION_PERIOD, "59"))))
                + "; 1209601 s -> " + outcome(admin.createQueue(b -> b.queueName(base + "-long")
                .attributes(Map.of(QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209601")))));
    }

    // ------------------------------------------------------------------ SPK-017

    @Test
    @DisplayName("SPK-017 publication latency against LocalStack is well below the 500 ms per-attempt timeout")
    void publicationLatency() {
        LocalStackSqsSupport.Queues own = LocalStackSqsSupport.createQueues();
        String body = "{\"schemaVersion\":1,\"messageType\":\"OrderProcessingRequested\",\"orderId\":\"%s\","
                + "\"eventId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"occurredAt\":\"2026-10-04T10:00:00Z\","
                + "\"correlationId\":\"spk017\"}";
        Map<String, MessageAttributeValue> attributes = Map.of(
                "messageType", MessageAttributeValue.builder().dataType("String").stringValue("OrderProcessingRequested").build(),
                "schemaVersion", MessageAttributeValue.builder().dataType("Number").stringValue("1").build(),
                "publisher", MessageAttributeValue.builder().dataType("String").stringValue("api").build());
        for (int warmup = 0; warmup < 20; warmup++) {
            publication.sendMessage(b -> b.queueUrl(own.orders()).messageBody(body.formatted("warmup"))).join();
        }
        List<Long> sequential = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            String orderId = UUID.randomUUID().toString();
            long started = System.nanoTime();
            publication.sendMessage(b -> b.queueUrl(own.orders()).messageBody(body.formatted(orderId))
                    .messageAttributes(attributes)).join();
            sequential.add(System.nanoTime() - started);
        }
        List<Long> concurrent = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> inFlight = new ArrayList<>();
        for (int index = 0; index < 208; index++) {
            long started = System.nanoTime();
            inFlight.add(publication.sendMessage(b -> b.queueUrl(own.orders()).messageBody(body.formatted("c"))
                            .messageAttributes(attributes))
                    .whenComplete((ok, error) -> concurrent.add(System.nanoTime() - started)));
            if (inFlight.size() == 16) {
                CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
                inFlight.clear();
            }
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        System.out.println("SPK-017 sequential (300): " + percentiles(sequential));
        System.out.println("SPK-017 concurrent, 16 in flight (208): " + percentiles(concurrent));
        assertThat(percentile(sequential, 99)).isLessThan(Duration.ofMillis(250).toNanos());
        assertThat(percentile(concurrent, 99)).isLessThan(Duration.ofMillis(500).toNanos());
    }

    // ------------------------------------------------------------------ SPK-018

    @Test
    @DisplayName("SPK-018 non-retryable errors from LocalStack: missing queue, invalid message contents, invalid attribute, oversized body")
    void nonRetryableErrors() {
        String missing = queues.orders().replace("ticketing-orders", "does-not-exist");
        Throwable noQueue = failure(publication.sendMessage(b -> b.queueUrl(missing).messageBody("x")));
        Throwable invalidContents = failure(publication.sendMessage(b -> b.queueUrl(queues.orders())
                .messageBody("bad\u0000body")));
        Throwable invalidAttribute = failure(publication.sendMessage(b -> b.queueUrl(queues.orders()).messageBody("x")
                .messageAttributes(Map.of("messageType", MessageAttributeValue.builder().dataType("String").build()))));
        Throwable tooLarge = failure(publication.sendMessage(b -> b.queueUrl(queues.orders())
                .messageBody("x".repeat(1_048_577))));
        System.out.println("SPK-018 missing queue -> " + describe(noQueue));
        System.out.println("SPK-018 invalid contents -> " + describe(invalidContents));
        System.out.println("SPK-018 invalid attribute -> " + describe(invalidAttribute));
        System.out.println("SPK-018 body of 1 MiB + 1 byte -> " + describe(tooLarge));
        for (Throwable error : List.of(noQueue, invalidContents, invalidAttribute, tooLarge)) {
            assertThat(error).isInstanceOf(AwsServiceException.class);
            AwsServiceException service = (AwsServiceException) error;
            assertThat(service.statusCode()).isBetween(400, 499);
            assertThat(service.isThrottlingException()).isFalse();
        }
    }

    @Test
    @DisplayName("SPK-018 a stopped or unreachable endpoint fails as SdkClientException and a paused one as a timeout")
    void connectionErrors() throws IOException {
        try (GenericContainer<?> transientStack = new GenericContainer<>(LocalStackSqsSupport.IMAGE)
                .withExposedPorts(4566)
                .withEnv("SERVICES", "sqs")
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {
            transientStack.start();
            SqsConnectionSettings connection = new SqsConnectionSettings(URI.create(
                    "http://" + transientStack.getHost() + ":" + transientStack.getMappedPort(4566)), "us-east-1");
            try (SqsAsyncClient client = SqsClientFactory.forPublication(connection, LocalStackSqsSupport.localCredentials())) {
                String url = client.createQueue(b -> b.queueName("spk018")).join().queueUrl();
                transientStack.getDockerClient().pauseContainerCmd(transientStack.getContainerId()).exec();
                StepVerifier.create(Mono.fromFuture(() -> client.sendMessage(b -> b.queueUrl(url).messageBody("x")))
                                .timeout(Duration.ofMillis(500))
                                .subscribeOn(Schedulers.parallel()))
                        .expectError(TimeoutException.class)
                        .verify(Duration.ofSeconds(5));
                System.out.println("SPK-018 paused endpoint -> java.util.concurrent.TimeoutException (adapter timeout 500 ms)");
                transientStack.getDockerClient().unpauseContainerCmd(transientStack.getContainerId()).exec();
                transientStack.stop();
                Throwable stopped = failure(client.sendMessage(b -> b.queueUrl(url).messageBody("x")));
                System.out.println("SPK-018 stopped container -> " + describe(stopped));
                assertThat(stopped).isInstanceOf(SdkClientException.class);
            }
        }
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (SqsAsyncClient client = SqsClientFactory.forPublication(
                new SqsConnectionSettings(URI.create("http://127.0.0.1:" + closedPort), "us-east-1"),
                LocalStackSqsSupport.localCredentials())) {
            Throwable refused = failure(client.sendMessage(b -> b
                    .queueUrl("http://127.0.0.1:" + closedPort + "/000000000000/q").messageBody("x")));
            System.out.println("SPK-018 connection refused -> " + describe(refused));
            assertThat(refused).isInstanceOf(SdkClientException.class);
        }
    }

    @Test
    @DisplayName("SPK-018 service errors LocalStack cannot produce (5xx, throttling, access denied) as the SDK reports them")
    void stubbedServiceErrors() throws IOException {
        List<StubCase> cases = List.of(
                new StubCase("internal error 500", 500, "InternalError", "InternalError;Receiver"),
                new StubCase("service unavailable 503", 503, "ServiceUnavailable", "ServiceUnavailable;Receiver"),
                new StubCase("throttling 400", 400, "ThrottlingException", "ThrottlingException;Sender"),
                new StubCase("request throttled 403", 403, "com.amazonaws.sqs#RequestThrottled", "RequestThrottled;Sender"),
                new StubCase("too many requests 429", 429, "TooManyRequestsException", "TooManyRequestsException;Sender"),
                new StubCase("access denied 403", 403, "AccessDeniedException", "AccessDenied;Sender"),
                new StubCase("queue does not exist 400", 400, "com.amazonaws.sqs#QueueDoesNotExist",
                        "AWS.SimpleQueueService.NonExistentQueue;Sender"));
        for (StubCase scenario : cases) {
            HttpServer server = stub(scenario);
            try (SqsAsyncClient client = SqsClientFactory.forPublication(new SqsConnectionSettings(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "us-east-1"),
                    LocalStackSqsSupport.localCredentials())) {
                Throwable error = failure(client.sendMessage(b -> b.queueUrl("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/000000000000/q").messageBody("x")));
                System.out.println("SPK-018 stub " + scenario.name() + " -> " + describe(error));
                assertThat(error).isInstanceOf(AwsServiceException.class);
            } finally {
                server.stop(0);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    record StubCase(String name, int status, String type, String queryError) {
    }

    static HttpServer stub(StubCase scenario) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("{\"__type\":\"" + scenario.type() + "\",\"message\":\"stub\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
            exchange.getResponseHeaders().add("x-amzn-query-error", scenario.queryError());
            exchange.sendResponseHeaders(scenario.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static void changeVisibility(String queueUrl, Message message, int seconds) {
        admin.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                .visibilityTimeout(seconds)).join();
    }

    private static Message receiveOne(String queueUrl) {
        for (int attempt = 0; attempt < 10; attempt++) {
            List<Message> messages = admin.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1)
                    .waitTimeSeconds(2)
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).join().messages();
            if (!messages.isEmpty()) {
                return messages.getFirst();
            }
        }
        throw new AssertionError("no message received");
    }

    static Throwable failure(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException error) {
            return error.getCause();
        }
        throw new AssertionError("expected a failure");
    }

    private static String outcome(CompletableFuture<?> future) {
        try {
            future.join();
            return "accepted";
        } catch (CompletionException error) {
            return "rejected: " + describe(error.getCause());
        }
    }

    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.getClass().getName());
        if (error instanceof AwsServiceException service) {
            text.append(" status=").append(service.statusCode())
                    .append(" code=").append(service.awsErrorDetails() == null ? null : service.awsErrorDetails().errorCode())
                    .append(" throttling=").append(service.isThrottlingException())
                    .append(" retryableBySdk=").append(service.retryable());
        } else if (error instanceof SdkClientException client) {
            text.append(" retryableBySdk=").append(client.retryable())
                    .append(" cause=").append(client.getCause() == null ? null : client.getCause().getClass().getName());
        }
        return text.toString();
    }

    private static String percentiles(List<Long> nanos) {
        return "p50=%.1f ms p95=%.1f ms p99=%.1f ms max=%.1f ms".formatted(
                percentile(nanos, 50) / 1e6, percentile(nanos, 95) / 1e6, percentile(nanos, 99) / 1e6,
                percentile(nanos, 100) / 1e6);
    }

    private static long percentile(List<Long> nanos, int percentile) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
