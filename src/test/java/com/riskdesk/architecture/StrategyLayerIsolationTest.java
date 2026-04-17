package com.riskdesk.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layer discipline for the new probabilistic strategy engine. These rules make the
 * top-down funnel non-bypassable at compile time — a drift in Gemini or human
 * contributions can't silently re-introduce cross-layer coupling.
 *
 * <p>Complements the global {@link HexagonalArchitectureTest} with rules specific
 * to {@code domain.engine.strategy}.
 */
@AnalyzeClasses(
    packages = "com.riskdesk",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class
    }
)
class StrategyLayerIsolationTest {

    /**
     * The new strategy engine must stay framework-free (same constraint as the rest
     * of the domain, restated here because the package is new and we want a quick
     * signal if someone adds {@code @Service} to a domain class).
     */
    @ArchTest
    static final ArchRule strategy_engine_is_framework_free =
        noClasses()
            .that().resideInAPackage("..domain.engine.strategy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.servlet..",
                "javax.persistence..")
            .because("strategy engine must remain pure domain — no Spring, no JPA, no web");

    /**
     * Strategy engine must not reach back into the legacy playbook internals. The
     * only allowed dependency is the shared {@code Direction} enum (pure value).
     * This prevents the new engine from silently depending on the old 7/7 mess.
     */
    @ArchTest
    static final ArchRule strategy_does_not_depend_on_legacy_playbook_internals =
        noClasses()
            .that().resideInAPackage("..domain.engine.strategy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.riskdesk.domain.engine.playbook.calculator..",
                "com.riskdesk.domain.engine.playbook.detector..",
                "com.riskdesk.domain.engine.playbook.agent..",
                "com.riskdesk.domain.engine.playbook.event..",
                "com.riskdesk.domain.engine.playbook.reconciliation..")
            .because("the new engine must stay decoupled from the legacy playbook evaluator");

    /**
     * Every concrete {@code StrategyAgent} implementation must live under the
     * {@code agent} sub-package, keeping the discovery path predictable.
     */
    @ArchTest
    static final ArchRule agent_implementations_live_in_agent_package =
        classes()
            .that().implement(com.riskdesk.domain.engine.strategy.agent.StrategyAgent.class)
            .and().areNotInterfaces()
            .should().resideInAPackage("..domain.engine.strategy.agent..")
            .because("agent implementations are discovered via this package convention");

    /**
     * Every {@code Playbook} lives under {@code playbook} sub-package. Same reason:
     * discoverable location for selector wiring and tests.
     */
    @ArchTest
    static final ArchRule playbooks_live_in_playbook_package =
        classes()
            .that().implement(com.riskdesk.domain.engine.strategy.playbook.Playbook.class)
            .and().areNotInterfaces()
            .should().resideInAPackage("..domain.engine.strategy.playbook..");
}
