package com.nequi.paymentmock.domain;

/**
 * Configuration (rule or defaults) rejected by the domain rules of PM-IV-006 to PM-IV-008. The message names the
 * offending field and never contains input values.
 */
public class InvalidConfigurationException extends RuntimeException {

    public InvalidConfigurationException(String message) {
        super(message, null, false, false);
    }
}
