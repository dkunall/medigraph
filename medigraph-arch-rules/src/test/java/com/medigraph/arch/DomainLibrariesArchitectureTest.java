package com.medigraph.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The algorithm/domain libraries are the "domain" ring of every service and must stay framework-free. */
class DomainLibrariesArchitectureTest {

    private static final JavaClasses LIBS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.medigraph.common", "com.medigraph.crypto", "com.medigraph.mpi", "com.medigraph.ledger",
                    "com.medigraph.consent", "com.medigraph.prescription", "com.medigraph.icu", "com.medigraph.beds");

    @Test
    void librariesWereImported() {
        org.junit.jupiter.api.Assertions.assertTrue(LIBS.size() > 50, "imported " + LIBS.size());
    }

    @Test
    void domainLibrariesDoNotUseSpringServletOrJpa() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.servlet..", "jakarta.persistence..")
                .check(LIBS);
    }

    @Test
    void icuScoringCoreDoesNotDependOnKafka() {
        noClasses().that().resideInAnyPackage("com.medigraph.icu", "com.medigraph.icu.stream")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..", "com.medigraph.icu.kafka..")
                .check(LIBS);
    }

    @Test
    void algorithmsDoNotDependOnTheirCallers() {
        noClasses().that().resideInAnyPackage("..algo..", "..merkle..", "..geo..", "..shard..")
                .should().dependOnClassesThat().resideInAnyPackage("..raft..", "..chain..")
                .check(LIBS);
    }
}
