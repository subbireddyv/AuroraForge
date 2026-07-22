package io.auroraforge.core.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit tests – enforces Clean Architecture dependency rules at build time.
 *
 * Failing these tests means a developer has introduced a forbidden dependency
 * (e.g., a domain class importing a Spring annotation, or an application service
 * calling a JPA repository directly). These tests are the architectural guardrails.
 */
@DisplayName("Clean Architecture – Dependency Rules")
class CleanArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.auroraforge");
    }

    @Test
    @DisplayName("Domain layer must not depend on application, infrastructure, or presentation")
    void domainMustBeIsolated() {
        // domain.security is the deliberate Spring Security bridge (TenantPrincipal
        // extends JwtAuthenticationToken) and is exempt from the framework ban.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("..domain.security..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "..presentation..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate.."
                );
        rule.check(classes);
    }

    @Test
    @DisplayName("Application layer must not depend on infrastructure or presentation")
    void applicationMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure..",
                        "..presentation..",
                        "org.springframework.data..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "software.amazon.awssdk..",
                        "com.azure.."
                );
        rule.check(classes);
    }

    @Test
    @DisplayName("Infrastructure must not depend on presentation")
    void infrastructureMustNotDependOnPresentation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat()
                .resideInAPackage("..presentation..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Layered architecture dependencies are respected")
    void layeredArchitectureIsRespected() {
        ArchRule rule = layeredArchitecture()
                .consideringAllDependencies()
                .layer("Domain")         .definedBy("..domain..")
                .layer("Application")    .definedBy("..application..")
                .layer("Infrastructure") .definedBy("..infrastructure..")
                .layer("Presentation")   .definedBy("..presentation..")
                .whereLayer("Domain")         .mayOnlyBeAccessedByLayers("Application", "Infrastructure")
                .whereLayer("Application")    .mayOnlyBeAccessedByLayers("Infrastructure", "Presentation")
                .whereLayer("Infrastructure") .mayOnlyBeAccessedByLayers("Presentation")
                .whereLayer("Presentation")   .mayNotBeAccessedByAnyLayer();

        rule.check(classes);
    }

    @Test
    @DisplayName("Domain exceptions must extend DomainException")
    void domainExceptionsMustExtendBase() {
        ArchRule rule = classes()
                .that().resideInAPackage("..domain.exception..")
                .and().haveSimpleNameEndingWith("Exception")
                .should().beAssignableTo(
                        io.auroraforge.core.domain.exception.DomainException.class);
        rule.check(classes);
    }

    @Test
    @DisplayName("Domain entities must not be annotated with JPA annotations")
    void domainEntitiesMustNotHaveJpaAnnotations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain.model..")
                .should().beAnnotatedWith(jakarta.persistence.Entity.class);
        rule.check(classes);
    }

    @Test
    @DisplayName("Use case interfaces must reside in application.port.in")
    void useCasesInCorrectPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("UseCase")
                .should().resideInAPackage("..application.port.in..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Output ports must reside in application.port.out")
    void outputPortsInCorrectPackage() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Port")
                .should().resideInAPackage("..application.port.out..");
        rule.check(classes);
    }

    @Test
    @DisplayName("JPA entities must reside in infrastructure.persistence.entity")
    void jpaEntitiesInCorrectPackage() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage("..infrastructure.persistence.entity..");
        rule.check(classes);
    }

    @Test
    @DisplayName("REST controllers must reside in presentation.rest")
    void controllersInCorrectPackage() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(
                        org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage("..presentation.rest..");
        rule.check(classes);
    }
}
