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

    static ArchRule periodicSchedulerTriggersOnlyInboundPorts() {
        DescribedPredicate<JavaClass> allowed = JavaClass.Predicates
                .resideInAnyPackage("java..", "reactor..", "org.reactivestreams..",
                        "com.nequi.ticketing.application.port.in..",
                        "com.nequi.ticketing.domain.event..",
                        "com.nequi.ticketing.infrastructure.adapter.in.scheduler..")
                .or(HasName.Predicates.nameMatching(
                        ".*\\.infrastructure\\.adapter\\.in\\.sqs\\.ConsumptionGate(\\$.*)?").forSubtype())
                .as("Java, Reactor, inbound ports, the shard counts of the domain and the consumption gate");
        return classes()
                .that().resideInAPackage("..infrastructure.adapter.in.scheduler..")
                .should().onlyDependOnClassesThat(allowed)
                .because("ADR-028 and ADR-034: the periodic scheduler (CMP-014) only triggers the inbound ports of "
                        + "the worker processes; the reversal pause follows the consumption gate driven by the "
                        + "Payment Mock circuit, never the circuit breaker or an adapter directly");
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

    /**
     * Shortcuts of the domain that apply the approved default instead of the configured value (IV-012, IV-015).
     * Owner, name and parameter types: only the domain itself (and tests) may use them.
     */
    private static final Set<String> APPROVED_DEFAULT_SHORTCUTS = Set.of(
            "com.nequi.ticketing.domain.order.Order.startPayment(java.time.Instant)",
            "com.nequi.ticketing.domain.order.Order.rescheduleReversal(java.time.Instant)",
            "com.nequi.ticketing.domain.order.ReversalPlan.reschedule(java.time.Instant)",
            "com.nequi.ticketing.domain.order.PurchaseRequest.<init>(java.lang.String, java.util.List, java.lang.String)",
            "com.nequi.ticketing.domain.messaging.OrderMessagePolicy.decide("
                    + "com.nequi.ticketing.domain.messaging.OrderMessagePolicy$Snapshot, java.time.Instant)",
            "com.nequi.ticketing.domain.messaging.ProvisioningMessagePolicy.decide("
                    + "com.nequi.ticketing.domain.messaging.ProvisioningMessagePolicy$Snapshot)",
            "com.nequi.ticketing.domain.event.ShardingPolicy.availabilityShards(int)",
            "com.nequi.ticketing.domain.event.Event.create(java.lang.String, java.lang.String, java.lang.String, "
                    + "java.time.Instant, int, com.nequi.ticketing.domain.event.InventoryDefinition, java.time.Instant, "
                    + "com.nequi.ticketing.domain.event.InventoryLimits)",
            "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcess.shardCount()",
            "com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerSchedulerSettings.<init>("
                    + "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcessSettings, "
                    + "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcessSettings, "
                    + "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcessSettings, "
                    + "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcessSettings)");

    static ArchRule configuredRulesAreUsedOutsideTheDomain() {
        return noClasses()
                .that().resideOutsideOfPackage("..domain..")
                .and().doNotHaveFullyQualifiedName(
                        "com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerSchedulerSettings")
                .and().doNotHaveFullyQualifiedName(
                        "com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcess")
                .should(callCodeUnits(APPROVED_DEFAULT_SHORTCUTS))
                .because("IV-012 and IV-015 make the maximum of Tickets, the payment cutoff, the reversal schedule, "
                        + "the verification repairs and the shards configurable: production code must pass the "
                        + "configured value, never the approved-default shortcut of the domain");
    }

    private static ArchCondition<JavaClass> callCodeUnits(Set<String> codeUnits) {
        return new ArchCondition<>("call an approved-default shortcut") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getCodeUnitCallsFromSelf().forEach(call -> {
                    var target = call.getTarget();
                    String signature = target.getOwner().getName() + "." + target.getName() + "("
                            + String.join(", ", target.getRawParameterTypes().stream()
                                    .map(JavaClass::getName).toList()) + ")";
                    if (codeUnits.contains(signature)) {
                        events.add(SimpleConditionEvent.satisfied(call,
                                javaClass.getName() + " calls the approved-default shortcut " + signature));
                    }
                });
            }
        };
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

