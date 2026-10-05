package com.nequi.ticketing.bootstrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.bootstrap.it.RoleEnvironment.RoleContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SPK-025 (architecture §13 #12, RISK-011, ADR-024): duration of the provisioning of the maximum Event (50,000
 * Tickets, 500 batches of 100 written in requests of 25 with up to 4 in parallel, verified with consistent batch
 * reads) on DynamoDB Local, through the real roles with the approved values (no override). Success criterion of
 * the plan: documented measurement; the result is printed and recorded in the INC-010 report.
 */
class ProvisioningCapacitySpikeIT {

    private static RoleEnvironment environment;

    @BeforeAll
    static void startEnvironment() {
        environment = RoleEnvironment.start();
    }

    @AfterAll
    static void stopEnvironment() {
        environment.close();
    }

    @Test
    @DisplayName("SPK-025 AC-001 FG-002 VAL-008 the maximum Event of 50,000 Tickets is provisioned and enabled with exactly its capacity")
    void maximumEventProvisioning() {
        try (RoleContext api = environment.start("api", Map.of());
             RoleContext worker = environment.start("worker", Map.of())) {
            TicketingApi client = new TicketingApi(environment.client(api), environment.tokens);
            long start = System.nanoTime();
            TicketingApi.Reply created = client.createEvent(TicketingApi.eventBody("Maximum",
                    Instant.now().plus(Duration.ofDays(60)), 50, 1_000), null);
            assertThat(created.status()).as(created.body()).isEqualTo(202);
            String eventId = created.text("eventId");

            JsonNode enabled = client.awaitProvisioning(eventId, "ENABLED", Duration.ofMinutes(15));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(enabled.get("capacity").intValue()).isEqualTo(50_000);
            assertThat(enabled.get("provisionedTickets").intValue()).isEqualTo(50_000);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(client.availableCount(eventId)).isEqualTo(49_999));
            double run = environment.metric(worker, "ticketing_provisioning_duration_seconds_sum");
            System.out.println("SPK-025 50,000 Tickets provisioned and enabled in " + millis
                    + " ms (creation request to ENABLED observed by polling); provisioning run "
                    + Math.round(run * 1000) + " ms");
            assertThat(enabled.get("enabledAt").isNull()).isFalse();
            assertThat(worker.context().isRunning()).isTrue();
        }
    }
}
