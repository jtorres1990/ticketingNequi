package com.nequi.ticketing.infrastructure.adapter.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.OrderConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ProvisioningConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SqsConsumers;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SwitchableConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublication;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublisherSettings;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** CMP-021: the SQS resources of the composition open their client, start and stop in order and close it. */
class SqsResourcesTest {

    private static final SqsConnectionSettings UNREACHABLE =
            new SqsConnectionSettings(URI.create("http://127.0.0.1:1"), "us-east-1");
    private static final String ORDERS = "http://127.0.0.1:1/000000000000/ticketing-orders";
    private static final String PROVISIONING = "http://127.0.0.1:1/000000000000/ticketing-event-provisioning";

    @Test
    @DisplayName("CMP-021 CMP-011 the publication resource exposes the publisher of both queues and closes its client")
    void publication() {
        SqsPublication publication = SqsPublication.open(UNREACHABLE, SqsPublisherSettings.deployed(ORDERS, PROVISIONING),
                Instant::now, SqsEvents.NONE);
        assertThat(publication.publisher()).isNotNull();
        assertThat(publication.publisher().circuit()).isNotNull();
        publication.close();
    }

    @Test
    @DisplayName("CMP-021 CMP-012 CMP-025 ADR-037 the consumers start both loops and the ordered stop completes")
    void consumers() {
        List<String> failures = new CopyOnWriteArrayList<>();
        SqsEvents events = new SqsEvents() {
            @Override
            public void receiveFailed(String queue, Throwable cause, Duration nextAttemptIn) {
                failures.add(queue);
            }
        };
        SqsConsumers consumers = SqsConsumers.open(UNREACHABLE, OrderConsumerSettings.deployed(ORDERS),
                command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)),
                new SwitchableConsumptionGate(), ProvisioningConsumerSettings.deployed(PROVISIONING),
                command -> Mono.just(MessageDisposition.delete(DispositionReason.ENABLED)), events);

        assertThat(consumers.running()).isFalse();
        consumers.start();
        assertThat(consumers.running()).isTrue();
        StepVerifier.create(consumers.stop()).expectComplete().verify(Duration.ofSeconds(30));
        assertThat(consumers.running()).isFalse();
        consumers.close();
    }
}
