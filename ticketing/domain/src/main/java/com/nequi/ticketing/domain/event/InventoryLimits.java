package com.nequi.ticketing.domain.event;

import com.nequi.ticketing.domain.error.ValidationException;

public record InventoryLimits(
        int maximumCapacity,
        int maximumSections,
        int maximumRows,
        int maximumSeatsPerRow,
        int maximumComplimentaryRanges,
        int provisioningBatchSize) {

    public static final InventoryLimits DEPLOYED = new InventoryLimits(50_000, 100, 2_000, 1_000, 500, 100);

    public InventoryLimits {
        if (maximumCapacity < 1 || maximumSections < 1 || maximumRows < 1
                || maximumSeatsPerRow < 1 || maximumComplimentaryRanges < 0
                || provisioningBatchSize < 1) {
            throw new ValidationException("inventory limits must be positive");
        }
    }
}
