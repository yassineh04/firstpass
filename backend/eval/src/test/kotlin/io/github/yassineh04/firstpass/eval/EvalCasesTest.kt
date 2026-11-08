package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.diff.AddedLine
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.UnifiedDiffParser
import io.github.yassineh04.firstpass.core.filter.FileFilter
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.github.yassineh04.firstpass.llm.FakeLlmClient
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock

/** Checks the real cases in eval/cases, so that a badly written case fails here and not in a paid run. */
class EvalCasesTest {
    private val cases = CaseLoader.load(CASES)

    @Test
    fun `there are 10 cases, of which 2 clean ones, each named for what it holds`() {
        cases.size shouldBe 10
        cases.filter { it.clean }.map { it.id } shouldContainExactly listOf("clean-refactor", "clean-rename")
        for (case in cases) {
            withClue(case.id) { case.clean shouldBe case.id.startsWith("clean-") }
        }
    }

    @Test
    fun `every patch is well formed and every file in it is reviewable`() {
        for (case in cases) {
            val files = UnifiedDiffParser.parse(case.patch)
            withClue(case.id) {
                files.shouldNotBeEmpty()
                FileFilter(ReviewSettings()).filter(files).skipped.shouldBeEmpty()
                for (file in files) {
                    val hunkLines =
                        DiffAnnotator
                            .annotate(file)
                            .shouldNotBeNull()
                            .hunks
                            .sumOf { it.lines.size }
                    // The hunk headers must count every line: a short count would silently drop the end of a hunk.
                    hunkLines shouldBe
                        file.patch
                            .orEmpty()
                            .lines()
                            .count { it.isNotEmpty() && it[0] in " +-" }
                }
            }
        }
    }

    @Test
    fun `every expected problem is on a line added by the patch`() {
        for (case in cases) {
            val added =
                UnifiedDiffParser
                    .parse(case.patch)
                    .flatMap { file ->
                        DiffAnnotator
                            .annotate(
                                file,
                            ).shouldNotBeNull()
                            .hunks
                            .flatMap { it.lines }
                            .filterIsInstance<AddedLine>()
                            .map {
                                file.path to it.newLine
                            }
                    }
            for (problem in case.expected) {
                withClue("${case.id}: ${problem.path}:${problem.line}") {
                    added shouldContain
                        (problem.path to problem.line)
                }
            }
        }
    }

    @Test
    fun `without a model, only the hard-coded secret is found, by the deterministic redactor`() {
        val fake = ReviewProvider("fake", FakeLlmClient(), ProviderBudget(20_000, 6), Int.MAX_VALUE)

        val runs = EvalRunner(listOf(fake), Clock.systemUTC()).run(cases, runs = 1)

        runs.map { it.outcome }.toSet() shouldBe setOf("completed")
        runs.filter { it.match.matched.isNotEmpty() }.map { it.case.id } shouldContainExactly
            listOf("kotlin-hardcoded-secret")
        runs.flatMap { it.match.extra }.shouldBeEmpty()
    }

    private companion object {
        /** Tests run in backend/eval; the cases live at the repository root. */
        val CASES: Path = Path.of("..", "..", "eval", "cases")
    }
}
