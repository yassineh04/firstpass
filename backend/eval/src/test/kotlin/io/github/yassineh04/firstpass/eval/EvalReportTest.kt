package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EvalReportTest {
    private val settings = EvalSettings(LocalDate.parse("2026-11-07"), "groq", "openai/gpt-oss-120b", "1", runs = 2)

    @Test
    fun `names the report after the date and a file-safe model name`() {
        EvalReport.fileName(settings) shouldBe "2026-11-07-openai-gpt-oss-120b.md"
    }

    @Test
    fun `formats ratios the same way in every locale`() {
        Numbers.percent(0.756) shouldBe "76 %"
        Numbers.percent(null) shouldBe "n/a"
        Numbers.decimal(0.5) shouldBe "0.5"
        Numbers.decimal(null) shouldBe "n/a"
    }

    @Test
    fun `reports the metrics against their targets, per category, per case, and the token estimate`() {
        val bug = EvalCase("kotlin-bug", "Title", "Description", "", listOf(expected(PATH, 10)))
        val clean = EvalCase("clean-change", "Title", "Description", "", emptyList())
        val found = finding(PATH, 11)
        val extra = finding(PATH, 30, Category.SECURITY)
        val runs =
            listOf(
                CaseRun(
                    bug,
                    1,
                    "completed",
                    listOf(found, extra),
                    CaseMatch(listOf(expected(PATH, 10) to found), emptyList(), listOf(extra)),
                ),
                CaseRun(
                    clean,
                    1,
                    "unavailable",
                    emptyList(),
                    CaseMatch(emptyList(), emptyList(), emptyList()),
                    failedBatches = 1,
                ),
            )
        val calls =
            listOf(CallRecord("groq", 1000, 1100), CallRecord("groq", 1000, 1300), CallRecord("groq", 1000, null))

        val report = EvalReport.markdown(settings, runs, calls)

        report shouldContain "# Evaluation of 2026-11-07"
        report shouldContain "groq"
        report shouldContain "openai/gpt-oss-120b"
        report shouldContain "| Precision | 50 % (1/2) | 70 % or more |"
        report shouldContain "| Recall | 100 % (1/1) | 60 % or more |"
        // The clean case was not reviewed, so there is nothing to measure noise on.
        report shouldContain "| Noise | n/a | 1 or less |"
        report shouldContain "| bug | 1 | 1 | 1 | 0 |"
        report shouldContain "| security | 0 | 0 | 1 | 1 |"
        report shouldContain "| kotlin-bug | 1 | 1 | 1 | completed |"
        report shouldContain "| clean-change | 0 | 0 | 0 | unavailable (1 failed batch) |"
        report shouldContain "| 2 | 2000 | 2400 | 1.20 |"
    }

    @Test
    fun `says when no model was called`() {
        EvalReport.markdown(settings, emptyList(), emptyList()) shouldContain "No model call"
    }

    private companion object {
        const val PATH = "src/main/kotlin/Cart.kt"
    }
}
