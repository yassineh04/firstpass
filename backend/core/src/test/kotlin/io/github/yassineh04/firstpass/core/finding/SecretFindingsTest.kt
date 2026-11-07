package io.github.yassineh04.firstpass.core.finding

import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.redaction.SecretRedactor
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SecretFindingsTest {
    private val awsKey = listOf("AK", "IA", "W".repeat(16)).joinToString("")

    private val redacted =
        SecretRedactor.redact(
            DiffAnnotator
                .annotate(DiffFile("src/Config.kt", FileStatus.ADDED, "@@ -0,0 +1 @@\n+val key = \"$awsKey\""))
                .shouldNotBeNull(),
        )

    @Test
    fun `turns a detected secret into a critical security finding that never repeats the value`() {
        val finding = SecretFindings.of(redacted.secrets, listOf(redacted.file), ReviewLanguage.EN).single()

        finding.severity shouldBe Severity.CRITICAL
        finding.category shouldBe Category.SECURITY
        finding.line shouldBe 1
        finding.confidence shouldBe 1.0
        finding.title shouldBe "Possible secret in the code"
        finding.body shouldContain "an AWS access key"
        listOf(finding.title, finding.body).forEach { it shouldNotContain awsKey }
        finding.fingerprint shouldBe
            Fingerprint.of(
                "src/Config.kt",
                Category.SECURITY,
                "Possible secret in the code",
                "val key = \"[REDACTED:aws-access-key]\"",
            )
    }

    @Test
    fun `writes the finding in French when asked`() {
        val finding = SecretFindings.of(redacted.secrets, listOf(redacted.file), ReviewLanguage.FR).single()

        finding.title shouldBe "Secret possible dans le code"
        finding.body shouldContain "une clé d'accès AWS"
    }
}
