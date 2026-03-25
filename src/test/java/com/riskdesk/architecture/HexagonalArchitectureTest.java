package com.riskdesk.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
    packages = "com.riskdesk",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class
    }
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_outer_layers =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..presentation..", "..infrastructure..")
            .because("the domain layer must stay independent from application, presentation and infrastructure");

    @ArchTest
    static final ArchRule presentation_must_not_depend_on_infrastructure =
        noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .because("presentation should talk to application and domain contracts, not infrastructure details");

    @ArchTest
    static final ArchRule infrastructure_must_not_depend_on_application_or_presentation =
        noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..presentation..")
            .because("infrastructure should implement adapters and configurations without pulling in upper layers");

    @ArchTest
    static final ArchRule application_must_not_depend_on_presentation =
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .because("application must expose use-case contracts without depending on HTTP-layer DTOs or controllers");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .because("domain must stay framework-agnostic");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_persistence =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..",
                "javax.persistence.."
            )
            .because("domain should not be coupled to persistence concerns");
}
