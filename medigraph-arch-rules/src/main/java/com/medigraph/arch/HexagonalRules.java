package com.medigraph.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * The layering every service follows ("ports and adapters"):
 * <pre>
 *   adapter.in (web, messaging)  -->  application (use cases)  -->  domain
 *                                         |  port.out (interfaces)
 *   adapter.out (persistence, http) ------+  implements port.out
 *   config  wires everything (the only place that knows concrete adapters)
 * </pre>
 * Rules: domain and application are plain Java (no Spring, servlet, JPA or Jackson); inbound and outbound adapters
 * never reference each other; outbound ports are interfaces; no field injection anywhere.
 */
public final class HexagonalRules {

    private static final String[] FRAMEWORKS = {"org.springframework..", "jakarta.servlet..", "jakarta.persistence..",
            "com.fasterxml.jackson..", "org.apache.kafka..", "io.github.resilience4j.."};

    private HexagonalRules() {
    }

    public static List<ArchRule> rules() {
        return List.of(
                noClasses().that().resideInAPackage("..domain..")
                        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..")
                        .allowEmptyShould(true).as("domain depends on nothing outside itself"),
                noClasses().that().resideInAPackage("..domain..")
                        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
                        .allowEmptyShould(true).as("domain is framework-free"),
                noClasses().that().resideInAPackage("..application..")
                        .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..")
                        .allowEmptyShould(true).as("application does not know adapters or wiring"),
                noClasses().that().resideInAPackage("..application..")
                        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
                        .allowEmptyShould(true).as("application (use cases) is framework-free"),
                noClasses().that().resideInAPackage("..adapter.in..")
                        .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                        .allowEmptyShould(true).as("inbound adapters go through use cases, not outbound adapters"),
                noClasses().that().resideInAPackage("..adapter.out..")
                        .should().dependOnClassesThat().resideInAPackage("..adapter.in..")
                        .allowEmptyShould(true).as("outbound adapters do not call inbound adapters"),
                classes().that().resideInAPackage("..application.port.out..").and().areTopLevelClasses()
                        .and().areNotRecords().and().areNotEnums()
                        .should().beInterfaces()
                        .allowEmptyShould(true).as("outbound ports are interfaces"),
                noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                        .orShould().beAnnotatedWith("jakarta.inject.Inject")
                        .allowEmptyShould(true).as("constructor injection only"));
    }

    /** Imports production classes under {@code basePackage} and fails with every violation found. */
    public static void check(String basePackage) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
        if (classes.isEmpty()) {
            throw new AssertionError("no classes found under " + basePackage);
        }
        rules().forEach(r -> r.check(classes));
    }
}
