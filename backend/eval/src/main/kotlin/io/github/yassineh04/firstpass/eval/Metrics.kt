package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Finding

/**
 * The result of one case in one run. [outcome] is completed, unavailable or quota_exceeded;
 * [models] are the models that answered, and [failedBatches] the batches without a valid answer.
 */
internal data class CaseRun(
    val case: EvalCase,
    val run: Int,
    val outcome: String,
    val findings: List<Finding>,
    val match: CaseMatch,
    val models: List<String> = emptyList(),
    val failedBatches: Int = 0,
)

/** Counts for one category: expected problems, how many were found, findings published, false positives. */
internal data class CategoryMetrics(
    val category: Category,
    val expected: Int,
    val found: Int,
    val published: Int,
    val falsePositives: Int,
)

/**
 * Review quality over all runs, with counts summed over the runs. Precision is matched findings
 * over published findings (inline and summary), recall is expected problems found over expected
 * problems, and noise is the mean number of findings on clean cases. A ratio without any
 * denominator is null.
 */
internal data class Metrics(
    val published: Int,
    val matched: Int,
    val expected: Int,
    val cleanRuns: Int,
    val cleanFindings: Int,
    val perCategory: List<CategoryMetrics>,
) {
    val precision: Double? get() = ratio(matched, published)
    val recall: Double? get() = ratio(matched, expected)
    val noise: Double? get() = ratio(cleanFindings, cleanRuns)

    companion object {
        /** Runs whose review did not complete measure availability, not quality: they are left out. */
        fun of(runs: List<CaseRun>): Metrics {
            val completed = runs.filter { it.outcome == Outcomes.COMPLETED }
            val clean = completed.filter { it.case.clean }
            return Metrics(
                published = completed.sumOf { it.findings.size },
                matched = completed.sumOf { it.match.matched.size },
                expected = completed.sumOf { it.case.expected.size },
                cleanRuns = clean.size,
                cleanFindings = clean.sumOf { it.findings.size },
                perCategory = Category.entries.mapNotNull { category(it, completed) },
            )
        }

        /** The counts of [category], or null when it was neither expected nor reported. */
        private fun category(
            category: Category,
            runs: List<CaseRun>,
        ): CategoryMetrics? {
            val metrics =
                CategoryMetrics(
                    category = category,
                    expected = runs.sumOf { run -> run.case.expected.count { it.category == category } },
                    found = runs.sumOf { run -> run.match.matched.count { it.first.category == category } },
                    published = runs.sumOf { run -> run.findings.count { it.category == category } },
                    falsePositives = runs.sumOf { run -> run.match.extra.count { it.category == category } },
                )
            return metrics.takeIf { it.expected > 0 || it.published > 0 }
        }

        private fun ratio(
            numerator: Int,
            denominator: Int,
        ): Double? = if (denominator == 0) null else numerator.toDouble() / denominator
    }
}
