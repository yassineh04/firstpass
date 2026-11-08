package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.batch.Batch
import io.github.yassineh04.firstpass.core.batch.BatchPlan
import io.github.yassineh04.firstpass.core.batch.BatchPlanner
import io.github.yassineh04.firstpass.core.batch.NotReviewedFile
import io.github.yassineh04.firstpass.core.batch.TruncatedHunk
import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.filter.FileFilter
import io.github.yassineh04.firstpass.core.filter.SkipReason
import io.github.yassineh04.firstpass.core.filter.SkippedFile
import io.github.yassineh04.firstpass.core.finding.Deduplicator
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.FindingRanker
import io.github.yassineh04.firstpass.core.finding.FindingValidator
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.SecretFindings
import io.github.yassineh04.firstpass.core.finding.TextSanitizer
import io.github.yassineh04.firstpass.core.prompt.BoundarySource
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.quota.ProviderAllowance
import io.github.yassineh04.firstpass.core.quota.QuotaDecision
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.redaction.SecretRedactor

/**
 * Runs one review, from changed files to ranked findings, without any I/O of its own: the caller
 * passes the files and today's counters, and gets back what to publish.
 *
 * Steps: filter, annotate, redact secrets, plan batches for the first provider whose daily token
 * allowance accepts the review, check the daily quota, then send each batch with a fresh
 * boundary. An invalid answer drops only its batch. A rate limit, an unavailable provider or a
 * rejected request hands the remaining files to the next provider, which plans them again with its
 * own budget; a provider that failed is not used again in the same review. Findings are validated
 * per batch, then deduplicated and ranked.
 */
class ReviewOrchestrator(
    private val providers: List<ReviewProvider>,
    private val quotaPolicy: QuotaPolicy,
    private val boundaries: BoundarySource = BoundarySource.SECURE,
) {
    init {
        require(providers.isNotEmpty()) { "At least one provider is needed" }
        require(providers.map { it.name }.toSet().size == providers.size) { "Provider names must be unique" }
    }

    fun review(request: ReviewRequest): ReviewOutcome {
        val prepared = prepare(request)
        val first = select(providers, prepared, request.providerTokensToday)
        val decision = first?.let { quotaPolicy.checkReview(request.usage, it.plan.batches.size) }
        return when {
            first == null -> ReviewOutcome.QuotaExceeded(QuotaReason.PROVIDER_TOKENS)
            decision is QuotaDecision.Exceeded -> ReviewOutcome.QuotaExceeded(decision.reason)
            else -> ReviewRun(request, prepared).execute(first)
        }
    }

    private fun prepare(request: ReviewRequest): Prepared {
        val filtered = FileFilter(request.settings).filter(request.files)
        val annotated = filtered.reviewable.map { it.file to DiffAnnotator.annotate(it.file) }
        val redacted = annotated.mapNotNull { it.second }.map(SecretRedactor::redact)
        val files = redacted.map { it.file }
        val withoutHunks =
            annotated.filter { it.second == null }.map {
                SkippedFile(
                    it.first.path,
                    SkipReason.NO_PATCH,
                )
            }
        val prompts = PromptBuilder(request.settings)
        return Prepared(
            files = files,
            secrets = SecretFindings.of(redacted.flatMap { it.secrets }, files, request.settings.language),
            skipped = filtered.skipped + withoutHunks,
            prompts = prompts,
            overhead = prompts.overheadTokens(request.pullRequest),
        )
    }

    /** The first candidate whose daily token allowance accepts [files], planned with its own budget. */
    private fun select(
        candidates: List<ReviewProvider>,
        prepared: Prepared,
        tokensUsedToday: Map<String, Int>,
        files: List<AnnotatedFile> = prepared.files,
    ): Selection? =
        candidates.firstNotNullOfOrNull { provider ->
            val plan = BatchPlanner(provider.budget, prepared.overhead).plan(files)
            val needed = plan.batches.sumOf { it.estimatedTokens + provider.budget.outputTokens }
            val allowance = ProviderAllowance(provider.name, tokensUsedToday[provider.name] ?: 0, provider.tokensPerDay)
            Selection(provider, plan).takeIf { quotaPolicy.providerFor(listOf(allowance), needed) != null }
        }

    private class Prepared(
        val files: List<AnnotatedFile>,
        val secrets: List<Finding>,
        val skipped: List<SkippedFile>,
        val prompts: PromptBuilder,
        val overhead: Int,
    )

    private class Selection(
        val provider: ReviewProvider,
        val plan: BatchPlan,
    )

    /** The mutable state of one review while its batches are sent. */
    private inner class ReviewRun(
        private val request: ReviewRequest,
        private val prepared: Prepared,
    ) {
        private val validator = FindingValidator(request.settings)
        private val anchored = mutableListOf<Finding>()
        private val unanchored = mutableListOf<Finding>()
        private val summaries = mutableListOf<String>()
        private val failed = mutableListOf<FailedBatch>()
        private val notReviewed = mutableListOf<NotReviewedFile>()
        private val truncated = mutableListOf<TruncatedHunk>()
        private val answeredPaths = mutableSetOf<String>()
        private val usage = LinkedHashMap<String, ProviderUsage>()
        private val tried = mutableSetOf<String>()
        private var rejected = 0
        private var calls = 0
        private var answers = 0

        fun execute(first: Selection): ReviewOutcome {
            var selection: Selection? = first
            while (selection != null) {
                tried += selection.provider.name
                notReviewed += selection.plan.notReviewed
                truncated += selection.plan.truncated
                selection = send(selection)
            }
            val report = report()
            return if (calls > 0 && answers == 0) ReviewOutcome.Unavailable(report) else ReviewOutcome.Completed(report)
        }

        /** Sends the batches in order; returns the provider that takes over after a failure, if any. */
        private fun send(selection: Selection): Selection? {
            val batches = selection.plan.batches
            for ((index, batch) in batches.withIndex()) {
                val result = call(selection.provider, batch, index + 1, batches.size)
                if (result is LlmResult.Failure && result.reason != LlmFailure.INVALID_OUTPUT) {
                    return takeOver(selection.provider, batches.drop(index), result)
                }
            }
            return null
        }

        private fun call(
            provider: ReviewProvider,
            batch: Batch,
            number: Int,
            count: Int,
        ): LlmResult {
            val prompt = prepared.prompts.build(request.pullRequest, batch.files, number, count, boundaries)
            val result = provider.llm.review(LlmRequest(prompt, provider.budget.outputTokens))
            calls++
            when (result) {
                is LlmResult.Success -> {
                    accept(batch, result.review)
                    addUsage(provider, result.model, result.inputTokens, result.outputTokens)
                }

                is LlmResult.Failure -> {
                    if (result.reason == LlmFailure.INVALID_OUTPUT) {
                        failed += FailedBatch(batch.paths(), provider.name, result.reason, result.detail)
                    }
                    addUsage(provider, model = null, inputTokens = 0, outputTokens = 0)
                }
            }
            return result
        }

        private fun accept(
            batch: Batch,
            review: ModelReview,
        ) {
            val validated = validator.validate(review.comments, batch.files)
            anchored += validated.anchored
            unanchored += validated.unanchored
            rejected += validated.rejected
            TextSanitizer.clean(review.summary, MAX_SUMMARY).takeIf { it.isNotBlank() }?.let { summaries += it }
            answeredPaths += batch.paths()
            answers++
        }

        private fun addUsage(
            provider: ReviewProvider,
            model: String?,
            inputTokens: Int,
            outputTokens: Int,
        ) {
            val current = usage[provider.name] ?: ProviderUsage(provider.name, null, 0, 0, 0)
            usage[provider.name] =
                ProviderUsage(
                    provider = provider.name,
                    model = model ?: current.model,
                    calls = current.calls + 1,
                    inputTokens = current.inputTokens + inputTokens,
                    outputTokens = current.outputTokens + outputTokens,
                )
        }

        /**
         * Hands the files of [remaining] to the next provider that was not tried yet, if its daily
         * tokens and today's call limit allow it; otherwise reports them as failed.
         */
        private fun takeOver(
            failedProvider: ReviewProvider,
            remaining: List<Batch>,
            failure: LlmResult.Failure,
        ): Selection? {
            val files = remaining.flatMap { it.files }
            val usageSoFar = request.usage.copy(globalLlmCalls = request.usage.globalLlmCalls + calls)
            val next =
                select(providers.filter { it.name !in tried }, prepared, tokensUsedToday(), files)
                    ?.takeIf { quotaPolicy.checkReview(usageSoFar, it.plan.batches.size) == QuotaDecision.Allowed }
            if (next == null) {
                failed +=
                    FailedBatch(
                        files.map { it.file.path }.distinct(),
                        failedProvider.name,
                        failure.reason,
                        failure.detail,
                    )
            }
            return next
        }

        private fun tokensUsedToday(): Map<String, Int> =
            request.providerTokensToday +
                usage.mapValues { (name, used) ->
                    (request.providerTokensToday[name] ?: 0) + used.inputTokens + used.outputTokens
                }

        private fun report(): ReviewReport {
            val anchoredFindings = prepared.secrets + anchored
            val unique = Deduplicator.deduplicate(anchoredFindings + unanchored, request.alreadyPublished)
            val anchoredSet = anchoredFindings.toSet()
            val (inline, outside) = unique.partition { it in anchoredSet }
            val ranked = FindingRanker.rank(inline, outside, request.settings.maxComments)
            return ReviewReport(
                inline = ranked.inline,
                summary = ranked.summary,
                modelSummaries = summaries.toList(),
                skipped = prepared.skipped,
                // One entry per file; partial when some of its lines got an answer.
                notReviewed =
                    notReviewed.map { it.path }.distinct().map { NotReviewedFile(it, partial = it in answeredPaths) },
                truncated = truncated.distinct(),
                failedBatches = failed.toList(),
                usage = usage.values.toList(),
                rejected = rejected,
                promptVersion = PromptBuilder.PROMPT_VERSION,
            )
        }
    }

    private companion object {
        /** The output schema allows 600 characters for the summary of a batch. */
        const val MAX_SUMMARY = 600

        fun Batch.paths(): List<String> = files.map { it.file.path }.distinct()
    }
}
