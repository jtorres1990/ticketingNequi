package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import java.util.Objects;

/** AP-018 candidate read from the provisioning range of {@code GSI1} (eventually consistent). */
public record StalledProvisioning(String eventId, Instant lastProgressAt, int republishCount) {

    public StalledProvisioning {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(lastProgressAt, "lastProgressAt");
    }
}
