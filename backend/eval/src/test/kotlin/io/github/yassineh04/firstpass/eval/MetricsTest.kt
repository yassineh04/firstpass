package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MetricsTest {
    private val bugCase = EvalCase("bug", "Title", "Description", "", listOf(expected(PATH, 10)))
    private val cleanCase = EvalCase("clean", "Title", "Description", "", emptyList())

    @Test
    fun `sums the counts over every run and derives precision, recall and noise`() {
        val match = finding(PATH, 11)
        val wrongCategory = finding(PATH, 20, Category.SECURITY)
        val noise = finding("src/Clean.kt", 5)
        val runs =
            listOf(
                run(bugCase, 1, CaseMatch(listOf(expected(PATH, 10) to match), emptyList(), listOf(wrongCategory))),
                run(bugCase, 2, CaseMatch(emptyList(), listOf(expected(PATH, 10)), emptyList())),
                run(cleanCase, 1, CaseMatch(emptyList(), emptyList(), listOf(noise))),
                run(cleanCase, 2, CaseMatch(emptyList(), emptyList(), emptyList())),
            )

        val metrics = Metrics.of(runs)

        metrics.published shouldBe 3
        metrics.matched shouldBe 1
        metrics.expected shouldBe 2
        metrics.precision shouldBe 1.0 / 3
        metrics.recall shouldBe 0.5
        metrics.noise shouldBe 0.5
        metrics.perCategory shouldContainExactly
            listOf(
                CategoryMetrics(Category.BUG, expected = 2, found = 1, published = 2, falsePositives = 1),
                CategoryMetrics(Category.SECURITY, expected = 0, found = 0, published = 1, falsePositives = 1),
            )
    }

    @Test
    fun `leaves a ratio empty when there is nothing to divide by`() {
        val metrics =
            Metrics.of(
                listOf(run(bugCase, 1, CaseMatch(emptyList(), listOf(expected(PATH, 10)), emptyList()))),
            )

        metrics.precision.shouldBeNull()
        metrics.noise.shouldBeNull()
        metrics.recall shouldBe 0.0
    }

    @Test
    fun `leaves out runs whose review did not complete, which measure availability, not quality`() {
        val unavailable =
            run(
                bugCase,
                1,
                CaseMatch(emptyList(), listOf(expected(PATH, 10)), emptyList()),
            ).copy(outcome = "unavailable")

        val metrics = Metrics.of(listOf(unavailable))

        metrics.expected shouldBe 0
        metrics.recall.shouldBeNull()
    }

    private fun run(
        case: EvalCase,
        number: Int,
        match: CaseMatch,
    ) = CaseRun(case, number, "completed", match.matched.map { it.second } + match.extra, match)

    private companion object {
        const val PATH = "src/main/kotlin/Cart.kt"
    }
}
