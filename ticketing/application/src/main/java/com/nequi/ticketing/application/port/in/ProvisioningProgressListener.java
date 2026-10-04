package com.nequi.ticketing.application.port.in;

/** Progress callback of the provisioning use case; it must not block (NFR-003). */
@FunctionalInterface
public interface ProvisioningProgressListener {

    ProvisioningProgressListener NONE = provisionedBatches -> { };

    /** Called after a batch was written; {@code provisionedBatches} counts the batches written so far. */
    void batchWritten(int provisionedBatches);
}
