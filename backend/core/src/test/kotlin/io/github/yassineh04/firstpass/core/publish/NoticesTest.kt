package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class NoticesTest {
    @Test
    fun `explains why a pull request above the size limit is not reviewed, with numbers in the reader's format`() {
        val changed = String.format(Locale.FRANCE, "%,d", 2340)
        val limit = String.format(Locale.FRANCE, "%,d", 1500)

        Notices.tooLarge(2340, 1500, ReviewLanguage.EN) shouldBe
            "FirstPass did not review this pull request: it changes 2,340 lines, more than the limit of 1,500. " +
            "Split it into smaller pull requests, or raise `max_changed_lines` in `.firstpass.yml`."
        Notices.tooLarge(2340, 1500, ReviewLanguage.FR) shouldBe
            "FirstPass n'a pas relu cette pull request : elle modifie $changed lignes, " +
            "au-delà de la limite de $limit. " +
            "Découpez-la en pull requests plus petites, ou relevez `max_changed_lines` dans `.firstpass.yml`."
    }

    @Test
    fun `titles the check run while running, without findings, with findings and without any answer`() {
        val findings =
            listOf(
                finding(severity = Severity.MAJOR),
                finding(severity = Severity.MINOR),
                finding(severity = Severity.MINOR),
            )

        CheckRunTexts.NAME shouldBe "FirstPass"
        CheckRunTexts.inProgress(ReviewLanguage.EN) shouldBe
            CheckRunText("Review in progress", "FirstPass is reviewing the changed lines.")
        CheckRunTexts.completed(emptyList(), ReviewLanguage.EN) shouldBe
            CheckRunText("No issues found", "FirstPass found no issues in the changed lines.")
        CheckRunTexts.completed(findings, ReviewLanguage.EN) shouldBe
            CheckRunText("Found 3 issues", "1 major, 2 minor. See the review comments on the pull request.")
        CheckRunTexts.completed(findings.take(1), ReviewLanguage.FR) shouldBe
            CheckRunText("1 problème trouvé", "1 majeur. Voir les commentaires de revue sur la pull request.")
        CheckRunTexts.unavailable(ReviewLanguage.FR).title shouldBe "Revue indisponible"
    }
}
