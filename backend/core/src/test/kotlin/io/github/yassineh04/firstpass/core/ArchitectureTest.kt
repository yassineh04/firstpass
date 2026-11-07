package io.github.yassineh04.firstpass.core

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Guards the hexagonal architecture: the domain module must stay free of cloud SDKs,
 * frameworks and HTTP clients, so that it can be tested without a network.
 */
class ArchitectureTest {
    private val coreClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CORE_PACKAGE)

    @Test
    fun `core does not depend on AWS or Spring or HTTP clients`() {
        // Phase 0: core has no production classes yet, so the rule is allowed to match nothing.
        // Remove allowEmptyShould once core has classes (tracked in a GitHub issue).
        noClasses()
            .that()
            .resideInAPackage("$CORE_PACKAGE..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "software.amazon..",
                "com.amazonaws..",
                "org.springframework..",
                "java.net.http..",
                "okhttp3..",
                "io.ktor..",
                "org.apache.hc..",
                "org.apache.http..",
                "retrofit2..",
            ).because("core is the hexagonal domain and must be testable without network, AWS or Spring")
            .allowEmptyShould(true)
            .check(coreClasses)
    }

    private companion object {
        const val CORE_PACKAGE = "io.github.yassineh04.firstpass.core"
    }
}
