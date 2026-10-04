package com.nequi.ticketing.application.port.out;

import java.time.Instant;

/** Outbound port: current server instant in UTC (ADR-034). */
@FunctionalInterface
public interface Clock {

    Instant now();
}
