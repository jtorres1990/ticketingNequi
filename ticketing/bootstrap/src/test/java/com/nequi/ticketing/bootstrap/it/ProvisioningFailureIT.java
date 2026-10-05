package com.nequi.ticketing.bootstrap.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.bootstrap.it.RoleEnvironment.RoleContext;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer;
import com.nequi.ticketing.infrastructure.adapter.sqs.LocalStackSqsSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Failed provisioning by role (plan INC-010, AC-040 flow, ADR-024): the {@code api} creates the Event and publishes
 * MSG-002 to a provisioning queue that no worker consumes, so the provisioning never progresses; the {@code worker}
 * cleanup process (CMP-015) detects it as stalled and, with no republication left, marks it {@code FAILED}
 * (AP-026, ST-013). Worker overrides by environment (TC-012): stalled threshold 2 s (approved 3 min), maximum
 * republications 0 (approved 3) and cleanup period 1 s (approved 60 s), only to make the flow observable quickly.
 */
class ProvisioningFailureIT {

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
    @DisplayName("AC-040 ERR-018 ST-013 FR-020 a stalled provisioning ends FAILED: never listed nor sellable, FAILED for the ADMIN")
    void stalledProvisioningEndsFailed() {
        LocalStackSqsSupport.Queues unconsumed = LocalStackSqsSupport.createQueues();
        try (RoleContext api = environment.start("api", Map.of(
                "ticketing.sqs.provisioning-queue-url", unconsumed.provisioning()));
             RoleContext worker = environment.start("worker", Map.of(
                     "ticketing.worker.stalled-provisioning-threshold", "2s",
                     "ticketing.worker.maximum-provisioning-republications", "0",
                     "ticketing.scheduler.provisioning-cleanup.period", "1s",
                     "ticketing.scheduler.provisioning-cleanup.failure-initial-wait", "1s",
                     "ticketing.scheduler.provisioning-cleanup.failure-max-wait", "5s"))) {
            TicketingApi client = new TicketingApi(environment.client(api), environment.tokens);
            TicketingApi.Reply created = client.createEvent(TicketingApi.eventBody("Stalled",
                    Instant.now().plus(Duration.ofDays(10)), 2, 10), null);
            assertThat(created.status()).as(created.body()).isEqualTo(202);
            String eventId = created.text("eventId");

            JsonNode failed = client.awaitProvisioning(eventId, "FAILED", Duration.ofSeconds(60));

            assertThat(failed.get("failedAt").isNull()).isFalse();
            assertThat(failed.get("enabledAt").isNull()).isTrue();
            TicketingApi.Reply availability = client.availability(TestTokenIssuer.CUSTOMER_A, eventId);
            assertThat(availability.status()).isEqualTo(404);
            assertThat(availability.text("code")).isEqualTo("EVENT_NOT_FOUND");
            TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_A, eventId, "A-1-2");
            assertThat(purchase.status()).isEqualTo(404);
            TicketingApi.Reply listing = client.get("/api/v1/events?limit=100",
                    environment.tokens.token(TestTokenIssuer.CUSTOMER_A));
            assertThat(listing.status()).isEqualTo(200);
            assertThat(listing.body()).doesNotContain(eventId);
            assertThat(environment.metric(worker, "ticketing_provisioning_total{outcome=\"failed\"}"))
                    .isGreaterThanOrEqualTo(1.0);
        }
    }
}
