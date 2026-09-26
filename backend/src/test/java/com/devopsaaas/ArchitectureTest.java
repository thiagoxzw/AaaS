package com.devopsaaas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.devopsaaas.shared.security.PublicEndpoint;
import com.devopsaaas.tool.api.Tool;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                    "com.devopsaaas.shared..", "java..", "javax..", "jakarta..", "org.springframework..",
                    "org.slf4j..",
                    // build-time only: justified SpotBugs suppressions
                    "edu.umd.cs.findbugs.annotations..");

    /** Other modules see the user only as shared.security.CurrentUser; roles never leak out (doc 04, 4.2). */
    @ArchTest
    static final ArchRule no_module_depends_on_identity = noClasses()
            .that().resideOutsideOfPackage("com.devopsaaas.identity..")
            .should().dependOnClassesThat().resideInAPackage("com.devopsaaas.identity..");

    @ArchTest
    static final ArchRule audit_does_not_depend_on_domain_modules = noClasses()
            .that().resideInAPackage("com.devopsaaas.audit..")
            .should().dependOnClassesThat().resideInAnyPackage("com.devopsaaas.environment..");

    /** The tool framework builds on environment and audit, never the other way around. */
    @ArchTest
    static final ArchRule domain_modules_do_not_depend_on_tools = noClasses()
            .that().resideInAnyPackage("com.devopsaaas.environment..", "com.devopsaaas.audit..")
            .should().dependOnClassesThat().resideInAPackage("com.devopsaaas.tool..");

    /**
     * Tools are thin (docs/05-contratos-das-ferramentas.md, 2.1): persistence, auditing and HTTP belong to the
     * executor and to adapters. Empty until the first production tool arrives in slice 3.
     */
    @ArchTest
    static final ArchRule tools_do_not_touch_persistence_or_http = noClasses()
            .that().implement(Tool.class)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.jdbc..", "org.springframework.data..",
                    "org.springframework.web.client..", "java.net.http..", "com.devopsaaas.audit..")
            .allowEmptyShould(true);

    /**
     * Repositories extend the bare Repository interface, so an unscoped findById/findAll cannot be called by
     * accident: every lookup has to name the organization (RNF-SEG-14).
     */
    @ArchTest
    static final ArchRule repositories_do_not_inherit_unscoped_finders = noClasses()
            .should().beAssignableTo(CrudRepository.class)
            .orShould().beAssignableTo(PagingAndSortingRepository.class)
            .orShould().beAssignableTo(QueryByExampleExecutor.class)
            .orShould().beAssignableTo(JpaSpecificationExecutor.class);

    /** Deny by default: every endpoint declares its permission or is explicitly public (TM-B1-08). */
    @ArchTest
    static final ArchRule endpoints_declare_their_authorization = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .orShould().beAnnotatedWith(PublicEndpoint.class);
}
