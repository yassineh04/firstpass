package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.diff.UnifiedDiffParser
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.PullRequestInfo
import io.github.yassineh04.firstpass.core.review.ReviewOrchestrator
import io.github.yassineh04.firstpass.core.review.ReviewOutcome
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.review.ReviewRequest
import java.time.Clock

/**
 * Reviews every case [run] times with the same pipeline as the app, and matches the findings,
 * inline and summary alike, since both are published. The model sees each case's title, never its
 * description, which gives the answer away.
 */
internal class EvalRunner(
    private val providers: List<ReviewProvider>,
    private val clock: Clock,
) {
    fun run(
        cases: List<EvalCase>,
        runs: Int,
    ): List<CaseRun> {
        val orchestrator = ReviewOrchestrator(providers, QuotaPolicy(QuotaLimits(), clock))
        return (1..runs).flatMap { run ->
            cases.mapIndexed { index, case -> review(orchestrator, case, number = index + 1, run = run) }
        }
    }

    private fun review(
        orchestrator: ReviewOrchestrator,
        case: EvalCase,
        number: Int,
        run: Int,
    ): CaseRun {
        val outcome =
            orchestrator.review(
                ReviewRequest(
                    PullRequestInfo(number, case.title, description = null),
                    UnifiedDiffParser.parse(case.patch),
                ),
            )
        val report =
            when (outcome) {
                is ReviewOutcome.Completed -> outcome.report
                is ReviewOutcome.Unavailable -> outcome.report
                is ReviewOutcome.QuotaExceeded -> null
            }
        val findings = report?.let { it.inline + it.summary }.orEmpty()
        return CaseRun(
            case = case,
            run = run,
            outcome = Outcomes.of(outcome),
            findings = findings,
            match = Matcher.match(case.expected, findings),
            models =
                report
                    ?.usage
                    ?.mapNotNull { it.model }
                    .orEmpty()
                    .distinct(),
            failedBatches = report?.failedBatches?.size ?: 0,
        )
    }
}
