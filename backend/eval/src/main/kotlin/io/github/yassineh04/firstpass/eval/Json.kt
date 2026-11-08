package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.review.ReviewOutcome
import io.github.yassineh04.firstpass.core.review.ReviewReport
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The JSON mapper of the command line tools, with Kotlin support. Thread-safe once built. */
internal val JSON: JsonMapper = jsonMapper { addModule(kotlinModule()) }

/** The names given to a review outcome in the JSON output and in the evaluation report. */
internal object Outcomes {
    const val COMPLETED = "completed"
    const val UNAVAILABLE = "unavailable"
    const val QUOTA_EXCEEDED = "quota_exceeded"

    fun of(outcome: ReviewOutcome): String =
        when (outcome) {
            is ReviewOutcome.Completed -> COMPLETED
            is ReviewOutcome.Unavailable -> UNAVAILABLE
            is ReviewOutcome.QuotaExceeded -> QUOTA_EXCEEDED
        }
}

/** What the `review` command prints: the outcome, and the report when there is one. */
internal data class ReviewOutput(
    val outcome: String,
    val quotaReason: QuotaReason?,
    val report: ReviewReport?,
) {
    companion object {
        fun of(outcome: ReviewOutcome): ReviewOutput =
            when (outcome) {
                is ReviewOutcome.Completed -> ReviewOutput(Outcomes.of(outcome), null, outcome.report)
                is ReviewOutcome.Unavailable -> ReviewOutput(Outcomes.of(outcome), null, outcome.report)
                is ReviewOutcome.QuotaExceeded -> ReviewOutput(Outcomes.of(outcome), outcome.reason, null)
            }
    }
}
