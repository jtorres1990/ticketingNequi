package com.nequi.ticketing.application.error;

/**
 * Temporary technical unavailability of a dependency after the bounded retries of its adapter (ADR-035:
 * "indisponibilidad temporal tras agotar reintentos"). Outbound adapters signal it by extending this type, so
 * that the inbound adapter translates it into {@code SERVICE_UNAVAILABLE} without knowing the adapter. It is
 * never a functional result and never carries detail meant for the client.
 */
public abstract class DependencyUnavailableException extends RuntimeException {

    protected DependencyUnavailableException(String message) {
        super(message);
    }

    protected DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
