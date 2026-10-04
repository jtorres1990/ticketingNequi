package com.nequi.ticketing.application.port.out;

/** Outbound port: random, non-guessable identifiers for Orders and Events (ADR-032, ADR-034). */
public interface IdGenerator {

    String newOrderId();

    String newEventId();
}
