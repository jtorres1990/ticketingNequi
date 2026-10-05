package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsClientFactory;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsConnectionSettings;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.util.Objects;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * The consumption client and the two reactive consumer loops of the {@code worker} role (CMP-012, CMP-025;
 * ADR-029, ADR-039) as one resource for the composition (CMP-021): {@link #start()} starts both loops,
 * {@link #stop()} is the ordered stop of ADR-037 (no new receives, the messages in flight complete) and
 * {@link #close()} closes the client once the loops have stopped.
 */
public final class SqsConsumers implements AutoCloseable {

    private final SqsAsyncClient client;
    private final OrderQueueConsumer orders;
    private final ProvisioningQueueConsumer provisioning;

    private SqsConsumers(SqsAsyncClient client, OrderQueueConsumer orders, ProvisioningQueueConsumer provisioning) {
        this.client = client;
        this.orders = orders;
        this.provisioning = provisioning;
    }

    public static SqsConsumers open(SqsConnectionSettings connection, OrderConsumerSettings ordersSettings,
            ProcessOrderUseCase processOrder, ConsumptionGate ordersGate,
            ProvisioningConsumerSettings provisioningSettings, ProvisionEventUseCase provisionEvent, SqsEvents events) {
        Objects.requireNonNull(ordersSettings, "ordersSettings");
        Objects.requireNonNull(processOrder, "processOrder");
        Objects.requireNonNull(ordersGate, "ordersGate");
        Objects.requireNonNull(provisioningSettings, "provisioningSettings");
        Objects.requireNonNull(provisionEvent, "provisionEvent");
        Objects.requireNonNull(events, "events");
        SqsAsyncClient client = SqsClientFactory.forConsumption(connection);
        return new SqsConsumers(client,
                OrderQueueConsumer.create(client, ordersSettings, processOrder, ordersGate, events),
                ProvisioningQueueConsumer.create(client, provisioningSettings, provisionEvent, events));
    }

    public void start() {
        orders.start();
        provisioning.start();
    }

    /** Stops receiving on both queues; completes when the messages in flight have been processed. */
    public Mono<Void> stop() {
        return Mono.when(orders.stop(), provisioning.stop());
    }

    public boolean running() {
        return orders.running() || provisioning.running();
    }

    @Override
    public void close() {
        client.close();
    }
}
