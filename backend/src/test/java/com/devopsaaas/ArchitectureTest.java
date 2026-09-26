package com.devopsaaas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Module boundaries from docs/03-arquitetura.md, section 12. Rules grow as modules are added. */
@AnalyzeClasses(packages = "com.devopsaaas", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.devopsaaas.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule shared_does_not_depend_on_other_modules = classes()
            .that().resideInAPackage("com.devopsaaas.shared..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.devopsaaas.shared..", "java..", "jakarta..", "org.springframework..", "org.slf4j..",
                    // build-time only: justified SpotBugs suppressions
                    "edu.umd.cs.findbugs.annotations..");
}
