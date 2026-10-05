package com.nequi.paymentmock.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.paymentmock.archfixture.ForbiddenFixture;
import com.nequi.paymentmock.archfixture.forbidden.Neighbour;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * G5 structural rules, each with a negative control proving that it detects a violation:
 * no dependency on {@code ticketing} (ADR-034 boundary rule 7), no blocking API in production code (TC-003),
 * framework-free domain.
 */
class ArchitectureTest {

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nequi.paymentmock");
    static final JavaClasses FIXTURE = new ClassFileImporter().importClasses(ForbiddenFixture.class, Neighbour.class);

    static final Set<String> BLOCKING_REACTOR_METHODS = Set.of("block", "blockFirst", "blockLast", "blockOptional",
            "toIterable", "toStream");

    static ArchRule noDependencyOn(String packageIdentifier) {
        return noClasses().should().dependOnClassesThat().resideInAPackage(packageIdentifier);
    }

    static ArchRule noBlockingCalls() {
        DescribedPredicate<JavaMethodCall> blocking = new DescribedPredicate<>("a blocking API") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getName();
                return (owner.equals(Thread.class.getName()) && name.equals("sleep"))
                        || (owner.equals(Object.class.getName()) && name.equals("wait"))
                        || ((owner.equals("reactor.core.publisher.Mono") || owner.equals("reactor.core.publisher.Flux"))
                                && BLOCKING_REACTOR_METHODS.contains(name))
                        || (owner.startsWith("java.util.concurrent.") && (name.equals("get") || name.equals("join"))
                                && (owner.contains("Future")));
            }
        };
        return noClasses().should().callMethodWhere(blocking);
    }

    static ArchRule frameworkFree(String packageIdentifier) {
        return noClasses().that().resideInAPackage(packageIdentifier)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "reactor..",
                        "tools.jackson..", "com.fasterxml..", "io.netty..");
    }

    @Test
    void productionCodeHasNoDependencyOnTicketing() {
        noDependencyOn("com.nequi.ticketing..").check(PRODUCTION);
        assertThatThrownBy(() -> noDependencyOn("com.nequi.paymentmock.archfixture.forbidden..").check(FIXTURE))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void productionCodeUsesNoBlockingApi() {
        noBlockingCalls().check(PRODUCTION);
        assertThatThrownBy(() -> noBlockingCalls().check(FIXTURE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Thread.sleep").hasMessageContaining("Object.wait").hasMessageContaining("Mono.block");
    }

    @Test
    void domainIsFrameworkFree() {
        frameworkFree("com.nequi.paymentmock.domain..").check(PRODUCTION);
        assertThatThrownBy(() -> frameworkFree("com.nequi.paymentmock.archfixture..").check(FIXTURE))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void noActuatorOrManagementInfrastructure() {
        noDependencyOn("org.springframework.boot.actuate..").check(PRODUCTION);
        assertThat(PRODUCTION.containPackage("com.nequi.paymentmock.web")).isTrue();
    }
}
