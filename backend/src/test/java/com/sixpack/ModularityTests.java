package com.sixpack;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Fails the build when a module reaches into another module's internals (ADR-002). */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(SixPackApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }

    /** Spec §4.1: switching marketing off must never break checkout, so nothing may depend on it. */
    @Test
    void nothingOutsideMarketingDependsOnIt() {
        ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.sixpack.marketing..")
                .should().dependOnClassesThat().resideInAPackage("com.sixpack.marketing..")
                .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.sixpack"));
    }
}
