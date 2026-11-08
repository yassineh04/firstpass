package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Finding

/** How the findings of one review compare with the expected ones. */
internal data class CaseMatch(
    val matched: List<Pair<ExpectedFinding, Finding>>,
    val missed: List<ExpectedFinding>,
    val extra: List<Finding>,
)

/**
 * A finding matches an expected problem when it is on the same file, in the same category, and at
 * most [LINE_TOLERANCE] lines away (the distance to a multi-line finding is measured from its
 * nearest end). Each finding matches at most one problem, the closest one wins.
 */
internal object Matcher {
    const val LINE_TOLERANCE = 3

    fun match(
        expected: List<ExpectedFinding>,
        findings: List<Finding>,
    ): CaseMatch {
        val remaining = findings.toMutableList()
        val matched = mutableListOf<Pair<ExpectedFinding, Finding>>()
        val missed = mutableListOf<ExpectedFinding>()
        for (problem in expected) {
            val best =
                remaining
                    .filter { it.path == problem.path && it.category == problem.category }
                    .filter { distance(it, problem.line) <= LINE_TOLERANCE }
                    .minByOrNull { distance(it, problem.line) }
            if (best == null) {
                missed += problem
            } else {
                matched += problem to best
                remaining -= best
            }
        }
        return CaseMatch(matched, missed, remaining)
    }

    private fun distance(
        finding: Finding,
        line: Int,
    ): Int {
        val start = finding.startLine ?: finding.line
        return when {
            line < start -> start - line
            line > finding.line -> line - finding.line
            else -> 0
        }
    }
}
