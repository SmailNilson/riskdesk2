package com.riskdesk.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
    packages = "com.riskdesk",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class
    }
)
class HexagonalArchitectureTest {

    /*
     * These ignores document the current legacy debt explicitly instead of hiding it in docs only:
     * - domain.engine.backtest still reaches application services
     * - domain.alert.service still reaches presentation DTOs
     *
     * The rules still protect the rest of the codebase from introducing new cross-layer leaks.
     */

    @ArchTest
    static final ArchRule domain_must_not_depend_on_outer_layers =
        noClasses()
            .that().resideInAPackage("..domain..")
            .and(not(resideInAPackage("..domain.engine.backtest..")))
            .and(not(resideInAPackage("..domain.alert.service..")))
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
    static final ArchRule application_must_not_depend_on_presentation_controllers =
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..presentation.controller..")
            .because("application services should not know HTTP controllers");
}
