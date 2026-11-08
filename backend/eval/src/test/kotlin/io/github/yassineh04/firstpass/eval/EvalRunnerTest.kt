package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EvalRunnerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC)

    private val bugCase =
        EvalCase(
            id = "bug",
            title = "Add a lookup",
            description = "SECRET-HINT: the answer is on line 11",
            patch = patchAdding(BUG_PATH, "val a = 1", "val b = a!!", "val c = 3"),
            expected = listOf(expected(BUG_PATH, 11)),
        )
    private val cleanCase =
        EvalCase("clean", "Rename a value", "Nothing to find", patchAdding(CLEAN_PATH, "val renamed = 1"), emptyList())

    @Test
    fun `reviews every case in every run, matches the findings, and never shows the description to the model`() {
        val prompts = mutableListOf<String>()
        // Answers like a model that finds the bug one line off, plus one comment in the wrong category.
        val oracle =
            LlmPort { request ->
                prompts += request.prompt.user
                val comments =
                    if (BUG_PATH in request.prompt.user) {
                        listOf(comment(BUG_PATH, 12, Category.BUG), comment(BUG_PATH, 10, Category.SECURITY))
                    } else {
                        emptyList()
                    }
                LlmResult.Success(ModelReview("Reviewed.", comments), "oracle", "oracle-model", 10, 10)
            }

        val runs = EvalRunner(listOf(provider(oracle)), clock).run(listOf(bugCase, cleanCase), runs = 2)

        runs.map { it.case.id to it.run } shouldContainExactly
            listOf("bug" to 1, "clean" to 1, "bug" to 2, "clean" to 2)
        runs.map { it.outcome }.toSet() shouldBe setOf("completed")
        runs
            .first()
            .match.matched
            .map { it.first } shouldContainExactly listOf(expected(BUG_PATH, 11))
        runs
            .first()
            .match.extra
            .map { it.category } shouldContainExactly listOf(Category.SECURITY)
        runs.first().models shouldContainExactly listOf("oracle-model")
        prompts.first() shouldContain "Add a lookup"
        prompts.forEach { it shouldNotContain "SECRET-HINT" }

        val metrics = Metrics.of(runs)
        metrics.precision shouldBe 0.5
        metrics.recall shouldBe 1.0
        metrics.noise shouldBe 0.0
    }

    private fun provider(llm: LlmPort) = ReviewProvider("oracle", llm, ProviderBudget(20_000, 6), Int.MAX_VALUE)

    private fun comment(
        path: String,
        line: Int,
        category: Category,
    ) = ModelComment(path, null, line, Severity.MAJOR, category, "Problem $category", "Explained.", null, 0.9)

    private companion object {
        const val BUG_PATH = "src/main/kotlin/Lookup.kt"
        const val CLEAN_PATH = "src/main/kotlin/Rename.kt"
    }
}
