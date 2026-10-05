package com.nequi.ticketing.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.architecture.fixture.domain.ForbiddenBlockingFixture;
import com.nequi.ticketing.architecture.fixture.domain.ForbiddenDomainFixture;
import com.nequi.ticketing.architecture.fixture.infrastructure.adapter.out.payment.ForbiddenPaymentFixture;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureRuleControlTest {

    @Test
    @DisplayName("ADR-034 architecture control detects a forbidden domain dependency")
    void domainRuleDetectsAForbiddenDependency() {
        var fixture = new ClassFileImporter().importClasses(ForbiddenDomainFixture.class);

        assertThatThrownBy(() -> ArchitectureRules
                        .domainDependencies("..architecture.fixture.domain..")
                        .check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework");
    }

    @Test
    @DisplayName("ADR-034 boundary rule 7 control detects a payment adapter type outside the HTTP contract stack")
    void paymentBoundaryRuleDetectsAForeignDependency() {
        var fixture = new ClassFileImporter().importClasses(ForbiddenPaymentFixture.class);

        assertThatThrownBy(() -> ArchitectureRules.paymentAdapterKnowsOnlyTheHttpContract().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework.util.StringUtils");
    }

    @Test
    @DisplayName("NFR-003 architecture control detects a known blocking API call")
    void blockingRuleDetectsAForbiddenCall() {
        var fixture = new ClassFileImporter().importClasses(ForbiddenBlockingFixture.class);

        assertThatThrownBy(() -> ArchitectureRules.noBlockingCalls().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.lang.Thread#sleep");
    }
}

