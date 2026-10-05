package com.nequi.ticketing.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/**
 * Excludes the published test artifacts ({@code *-tests.jar} of application and infrastructure, used by the
 * component integration tests of INC-010) from the production classes analysed by the architecture rules.
 */
public final class ExcludeTestArtifacts implements ImportOption {

    @Override
    public boolean includes(Location location) {
        return !location.contains("-tests.jar") && !location.contains("/test-classes/");
    }
}
