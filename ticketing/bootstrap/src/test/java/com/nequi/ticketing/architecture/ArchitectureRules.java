package com.nequi.ticketing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

final class ArchitectureRules {

    private static final Set<String> BLOCKING_METHODS = Set.of(
            "java.lang.Thread#sleep",
            "reactor.core.publisher.Mono#block",
            "reactor.core.publisher.Mono#blockOptional",
            "reactor.core.publisher.Flux#blockFirst",
            "reactor.core.publisher.Flux#blockLast",
            "reactor.core.publisher.Flux#toIterable",
            "reactor.core.publisher.Flux#toStream");

    private static final Set<String> VIRTUAL_THREAD_METHODS = Set.of(
            "java.lang.Thread#startVirtualThread",
            "java.lang.Thread$Builder$OfVirtual#start",
            "java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor",
            "java.util.concurrent.Executors#newThreadPerTaskExecutor");

    private ArchitectureRules() {
    }

    static ArchRule domainDependencies(String domainPackage) {
        return noClasses()
                .that().resideInAPackage(domainPackage)
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", domainPackage)
                .because("ADR-034 requires domain to depend only on the Java standard library");
    }

    static ArchRule applicationDependencies() {
        return noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "java..", "org.reactivestreams..", "reactor..",
                        "com.nequi.ticketing.domain..", "com.nequi.ticketing.application..")
                .because("ADR-034 allows application to depend only on domain and Reactor types");
    }

    static ArchRule infrastructureDoesNotDependOnBootstrap() {
        return noClasses()
                .that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("..bootstrap..");
    }

    static ArchRule retryAndCircuitBreakerStayInOutboundAdapters() {
        DescribedPredicate<JavaClass> resilienceExceptRateLimiter = JavaClass.Predicates
                .resideInAnyPackage("reactor.util.retry..", "io.github.resilience4j..")
                .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("io.github.resilience4j.ratelimiter..")))
                .as("Reactor retry or Resilience4j types other than the rate limiter of the request guard");
        return noClasses()
                .that().resideOutsideOfPackage("..infrastructure.adapter.out..")
                .should().dependOnClassesThat(resilienceExceptRateLimiter)
                .because("ADR-035 locates retry and circuit breakers in outbound infrastructure adapters");
    }

    static ArchRule rateLimiterStaysInTheRequestGuard() {
        return noClasses()
                .that().resideOutsideOfPackage("..infrastructure.adapter.in.web..")
                .should().dependOnClassesThat().resideInAnyPackage("io.github.resilience4j.ratelimiter..")
                .because("ADR-032 places the per-subject rate limiter of API-004 in the request guard (CMP-017) "
                        + "of the HTTP adapter");
    }

    static ArchRule webServerAndSecurityTypesStayInTheWebAdapter() {
        return noClasses()
                .that().resideOutsideOfPackages("..infrastructure.adapter.in.web..", "..bootstrap..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.security..",
                        "org.springframework.web.server..",
                        "org.springframework.web.reactive.function.server..")
                .because("ADR-034 confines the HTTP entrypoint (CMP-001), security (CMP-002), error translation "
                        + "(CMP-016) and request guard (CMP-017) to the inbound web adapter; composition is bootstrap");
    }

    static ArchRule awsSdkTypesStayInAwsAdapters() {
        return noClasses()
                .that().resideOutsideOfPackages(
                        "..infrastructure.adapter.out.dynamodb..",
                        "..infrastructure.adapter.out.sqs..",
                        "..infrastructure.adapter.in.sqs..",
                        "..infrastructure.adapter.sqs..")
                .should().dependOnClassesThat().resideInAnyPackage("software.amazon.awssdk..")
                .because("ADR-034 confines AWS SDK types to DynamoDB and SQS adapters "
                        + "(SQS: publisher, consumers and their shared client and wire format)");
    }

    static ArchRule paymentAdapterTypesDoNotLeak() {
        DescribedPredicate<JavaClass> facade = HasName.Predicates.nameMatching(
                ".*\\.infrastructure\\.adapter\\.out\\.payment\\."
                        + "(PaymentMockGateway|PaymentGatewaySettings|PaymentEvents)(\\$.*)?")
                .forSubtype();
        DescribedPredicate<JavaClass> internalTypes = JavaClass.Predicates
                .resideInAPackage("..infrastructure.adapter.out.payment..")
                .and(DescribedPredicate.not(facade))
                .as("payment adapter types other than its facade (gateway, settings, events hook)");
        return noClasses()
                .that().resideOutsideOfPackage("..infrastructure.adapter.out.payment..")
                .should().dependOnClassesThat(internalTypes)
                .because("ADR-034 requires payment adapter types (wire format, transport, failures) to remain "
                        + "private to the adapter; composition only uses its facade");
    }

    static ArchRule paymentAdapterKnowsOnlyTheHttpContract() {
        return classes()
                .that().resideInAPackage("..infrastructure.adapter.out.payment..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "reactor..", "org.reactivestreams..", "io.netty..",
                        "org.springframework.http..", "org.springframework.web.reactive.function.client..",
                        "tools.jackson..",
                        "com.nequi.ticketing.application.port.out..",
                        "com.nequi.ticketing.infrastructure.adapter.out.payment..",
                        "com.nequi.ticketing.infrastructure.adapter.out.resilience..")
                .because("ADR-034 boundary rule 7: the payment adapter defines its own types and only knows the "
                        + "HTTP contract of the Payment Mock (no code shared with payment-mock)");
    }

    static ArchRule inboundAdaptersUseOnlyInboundPorts() {
        return noClasses()
                .that().resideInAPackage("..infrastructure.adapter.in..")
                .should().dependOnClassesThat().resideInAnyPackage("..application.port.out..")
                .because("ADR-034 requires inbound adapters to invoke inbound ports only");
    }

    static ArchRule useCasesDoNotDependOnInfrastructure() {
        return noClasses()
                .that().resideInAPackage("..application.usecase..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
                .because("ADR-034 requires use cases to reach infrastructure through outbound ports");
    }

    static ArchRule noBlockingCalls() {
        return classes().should(notCallMethods(BLOCKING_METHODS, "known blocking"));
    }

    static ArchRule noVirtualThreads() {
        return classes().should(notCallMethods(VIRTUAL_THREAD_METHODS, "virtual-thread"));
    }

    private static ArchCondition<JavaClass> notCallMethods(Set<String> forbiddenMethods, String description) {
        return new ArchCondition<>("not call " + description + " APIs") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    String signature = call.getTarget().getOwner().getName() + "#" + call.getTarget().getName();
                    if (forbiddenMethods.contains(signature)) {
                        events.add(SimpleConditionEvent.violated(
                                call,
                                javaClass.getName() + " calls forbidden API " + signature));
                    }
                }
            }
        };
    }
}

