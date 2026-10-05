package com.nequi.ticketing.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.nequi.ticketing",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JAVA = ArchitectureRules.domainDependencies("..domain..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_DOMAIN_AND_REACTOR =
            ArchitectureRules.applicationDependencies();

    @ArchTest
    static final ArchRule INFRASTRUCTURE_DOES_NOT_DEPEND_ON_BOOTSTRAP =
            ArchitectureRules.infrastructureDoesNotDependOnBootstrap();

    @ArchTest
    static final ArchRule RETRY_AND_CIRCUIT_BREAKER_STAY_IN_OUTBOUND_ADAPTERS =
            ArchitectureRules.retryAndCircuitBreakerStayInOutboundAdapters();

    @ArchTest
    static final ArchRule RATE_LIMITER_STAYS_IN_THE_REQUEST_GUARD = ArchitectureRules.rateLimiterStaysInTheRequestGuard();

    @ArchTest
    static final ArchRule WEB_SERVER_AND_SECURITY_TYPES_STAY_IN_THE_WEB_ADAPTER =
            ArchitectureRules.webServerAndSecurityTypesStayInTheWebAdapter();

    @ArchTest
    static final ArchRule AWS_SDK_TYPES_STAY_IN_AWS_ADAPTERS = ArchitectureRules.awsSdkTypesStayInAwsAdapters();

    @ArchTest
    static final ArchRule PAYMENT_ADAPTER_TYPES_DO_NOT_LEAK = ArchitectureRules.paymentAdapterTypesDoNotLeak();

    @ArchTest
    static final ArchRule PAYMENT_ADAPTER_KNOWS_ONLY_THE_HTTP_CONTRACT =
            ArchitectureRules.paymentAdapterKnowsOnlyTheHttpContract();

    @ArchTest
    static final ArchRule INBOUND_ADAPTERS_USE_ONLY_INBOUND_PORTS = ArchitectureRules.inboundAdaptersUseOnlyInboundPorts();

    @ArchTest
    static final ArchRule USE_CASES_DO_NOT_DEPEND_ON_INFRASTRUCTURE =
            ArchitectureRules.useCasesDoNotDependOnInfrastructure();

    @ArchTest
    static final ArchRule PRODUCTION_CODE_HAS_NO_BLOCKING_CALLS = ArchitectureRules.noBlockingCalls();

    @ArchTest
    static final ArchRule PRODUCTION_CODE_HAS_NO_VIRTUAL_THREADS = ArchitectureRules.noVirtualThreads();
}

