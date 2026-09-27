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
     * executor and to adapters.
     */
    @ArchTest
    static final ArchRule tools_do_not_touch_persistence_or_http = noClasses()
            .that().implement(Tool.class)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.jdbc..", "org.springframework.data..",
                    "org.springframework.web.client..", "java.net.http..", "com.devopsaaas.audit..",
                    "com.devopsaaas.integration..");

    /** The LLM port knows nothing of agents, tools or the domain: it only translates (ADR-0010). */
    @ArchTest
    static final ArchRule llm_module_is_independent = noClasses()
            .that().resideInAPackage("com.devopsaaas.llm..")
            .should().dependOnClassesThat().resideInAnyPackage("com.devopsaaas.agent..", "com.devopsaaas.tool..",
                    "com.devopsaaas.environment..", "com.devopsaaas.audit..", "com.devopsaaas.integration..");

    /** The agent sits on top: tools, environments and the LLM never depend on it. */
    @ArchTest
    static final ArchRule nothing_depends_on_the_agent = noClasses()
            .that().resideOutsideOfPackage("com.devopsaaas.agent..")
            .should().dependOnClassesThat().resideInAPackage("com.devopsaaas.agent..");

    /** The agent reaches runtimes only through tools and their policy, never through the port or an adapter. */
    @ArchTest
    static final ArchRule agent_does_not_call_runtimes_directly = noClasses()
            .that().resideInAPackage("com.devopsaaas.agent..")
            .should().dependOnClassesThat().resideInAnyPackage("com.devopsaaas.tool.container..",
                    "com.devopsaaas.integration..");

    /**
     * The diagnosis rules are pure: observed data in, findings out. Apart from their Spring wiring, they know
     * nothing of Docker, HTTP, the database or the agent (docs/05-contratos-das-ferramentas.md, 8.3).
     */
    @ArchTest
    static final ArchRule diagnostics_are_pure = classes()
            .that().resideInAPackage("com.devopsaaas.tool.diagnostics..")
            .and().doNotHaveSimpleName("DiagnosticsConfiguration")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.devopsaaas.tool.diagnostics..", "com.devopsaaas.tool.api..",
                    "com.devopsaaas.tool.container..", "java..", "org.springframework.boot.context.properties..");

    /** Tools see the ContainerRuntime port only; the Docker adapter is a detail behind it (doc 05, section 9). */
    @ArchTest
    static final ArchRule tool_module_does_not_depend_on_adapters = noClasses()
            .that().resideInAPackage("com.devopsaaas.tool..")
            .should().dependOnClassesThat().resideInAPackage("com.devopsaaas.integration..");

    /**
     * The adapter implements the port and nothing else: it cannot reach the policy, the executor, the
     * allowlist or the database, so it has no way to pick a target on its own.
     */
    @ArchTest
    static final ArchRule docker_adapter_only_knows_the_port = classes()
            .that().resideInAPackage("com.devopsaaas.integration.docker..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.devopsaaas.integration.docker..", "com.devopsaaas.tool.container..", "java..",
                    "jakarta.annotation..", "org.springframework.boot.context.properties..",
                    "org.springframework.http..", "org.springframework.web.client..", "org.springframework.web.util..",
                    "org.springframework.stereotype..", "tools.jackson..", "com.fasterxml.jackson.annotation..",
                    "io.micrometer.core..", "org.slf4j..",
                    // build-time only: justified SpotBugs suppressions
                    "edu.umd.cs.findbugs.annotations..");

    /** Only the Docker adapter talks HTTP to the outside; nothing else in the backend opens a raw HTTP client. */
    @ArchTest
    static final ArchRule only_adapters_use_http_clients = noClasses()
            .that().resideOutsideOfPackage("com.devopsaaas.integration..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.web.client.RestClient");

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
