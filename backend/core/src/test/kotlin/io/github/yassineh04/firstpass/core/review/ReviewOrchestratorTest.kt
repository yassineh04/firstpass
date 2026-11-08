package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.batch.BatchPlanner
import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.filter.SkipReason
import io.github.yassineh04.firstpass.core.filter.SkippedFile
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.quota.DailyUsage
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.redaction.SecretRedactor
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewOrchestratorTest {
    private val pr = PullRequestInfo(number = 7, title = "Add user lookup", description = "Adds a lookup by id.")
    private val overhead = PromptBuilder(ReviewSettings()).overheadTokens(pr)
    private val clock = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC)

    // Budgets sized from the estimate of one big file, whatever the estimator's calibration: one big
    // file per batch, or two. The slack is far smaller than a file, so a third one never fits.
    private val bigFileTokens = TokenEstimator.estimate(DiffAnnotator.annotate(bigFile(PATH_A)).shouldNotBeNull())
    private val oneFilePerBatch = ProviderBudget(inputTokensPerBatch = overhead + bigFileTokens + SLACK, maxBatches = 6)
    private val twoFilesPerBatch =
        ProviderBudget(inputTokensPerBatch = overhead + 2 * bigFileTokens + SLACK, maxBatches = 6)

    @Test
    fun `reviews the files and returns ranked findings, the cleaned model summary and the usage`() {
        val llm = ScriptedLlm(ok(comment(PATH_A, line = 11), summary = "Ask @octocat about the cache."))

        val report = completed(orchestrator(provider("primary", llm)).review(request(smallFile(PATH_A))))

        report.inline.map { it.path to it.line } shouldContainExactly listOf(PATH_A to 11)
        report.summary.shouldBeEmpty()
        report.modelSummaries shouldContainExactly listOf("Ask `@octocat` about the cache.")
        report.usage shouldContainExactly
            listOf(ProviderUsage("primary", MODEL, calls = 1, inputTokens = 100, outputTokens = 20))
        report.promptVersion shouldBe PromptBuilder.PROMPT_VERSION
        report.failedBatches.shouldBeEmpty()
    }

    @Test
    fun `reports a secret itself and never sends it to the model`() {
        val awsKey = listOf("AK", "IA", "Z".repeat(16)).joinToString("")
        val llm = ScriptedLlm(ok())
        val file = diffFile(PATH_A, "val name = \"lookup\"", "val key = \"$awsKey\"")

        val report = completed(orchestrator(provider("primary", llm)).review(request(file)))

        report.inline.map { Triple(it.line, it.severity, it.category) } shouldContainExactly
            listOf(Triple(11, Severity.CRITICAL, Category.SECURITY))
        llm.requests
            .single()
            .prompt.user shouldNotContain awsKey
        llm.requests
            .single()
            .prompt.user shouldContain "[REDACTED"
    }

    @Test
    fun `refuses the review before any model call when a daily limit is reached`() {
        val llm = ScriptedLlm(ok())
        val orchestrator = orchestrator(provider("primary", llm), limits = QuotaLimits(20, 300))

        orchestrator.review(request(smallFile(PATH_A), usage = DailyUsage(20, 0))) shouldBe
            ReviewOutcome.QuotaExceeded(QuotaReason.INSTALLATION_REVIEWS)
        orchestrator.review(request(smallFile(PATH_A), usage = DailyUsage(0, 300))) shouldBe
            ReviewOutcome.QuotaExceeded(QuotaReason.GLOBAL_LLM_CALLS)
        llm.requests.shouldBeEmpty()
    }

    @Test
    fun `uses the next provider when the first has no tokens left today, with batches sized for it`() {
        val primary = ScriptedLlm(ok())
        val secondary = ScriptedLlm(ok())
        val files = listOf(bigFile(PATH_A), bigFile(PATH_B))

        val report =
            completed(
                orchestrator(
                    provider("primary", primary, twoFilesPerBatch, tokensPerDay = 50_000),
                    provider("secondary", secondary, oneFilePerBatch),
                ).review(request(files, providerTokens = mapOf("primary" to 49_000))),
            )

        primary.requests.shouldBeEmpty()
        secondary.requests.size shouldBe 2
        secondary.requests.forEach { it.tokens() shouldBeLessThanOrEqual oneFilePerBatch.inputTokensPerBatch }
        report.usage.map { it.provider } shouldContainExactly listOf("secondary")
    }

    @Test
    fun `counts the expected answer tokens in the daily allowance of a provider`() {
        val file = bigFile(PATH_A)
        val annotated = listOf(SecretRedactor.redact(DiffAnnotator.annotate(file).shouldNotBeNull()).file)
        val inputTokens = BatchPlanner(twoFilesPerBatch, overhead).plan(annotated).batches.sumOf { it.estimatedTokens }
        val needed = inputTokens + twoFilesPerBatch.outputTokens

        fun outcomeWith(tokensLeft: Int) =
            orchestrator(provider("primary", ScriptedLlm(ok()), tokensPerDay = 100_000))
                .review(request(file, providerTokens = mapOf("primary" to 100_000 - tokensLeft)))

        outcomeWith(needed).shouldBeInstanceOf<ReviewOutcome.Completed>()
        outcomeWith(needed - 1) shouldBe ReviewOutcome.QuotaExceeded(QuotaReason.PROVIDER_TOKENS)
    }

    @Test
    fun `refuses the review when no provider has enough tokens left today`() {
        val llm = ScriptedLlm(ok())

        orchestrator(provider("primary", llm, tokensPerDay = 1_000))
            .review(request(bigFile(PATH_A), providerTokens = mapOf("primary" to 900))) shouldBe
            ReviewOutcome.QuotaExceeded(QuotaReason.PROVIDER_TOKENS)
        llm.requests.shouldBeEmpty()
    }

    @Test
    fun `switches to the fallback when a batch is rate limited, and re-plans the rest with its budget`() {
        val primary = ScriptedLlm(ok(comment(PATH_A, line = 12)), fail(LlmFailure.RATE_LIMITED))
        val fallback = ScriptedLlm(ok(comment(PATH_C, line = 13)), ok())
        val files = listOf(bigFile(PATH_A), bigFile(PATH_B), bigFile(PATH_C), bigFile(PATH_D))

        val report =
            completed(
                orchestrator(
                    provider("primary", primary, twoFilesPerBatch),
                    provider("fallback", fallback, oneFilePerBatch),
                ).review(request(files)),
            )

        primary.requests.size shouldBe 2
        fallback.requests.map { it.paths() } shouldContainExactly listOf(listOf(PATH_C), listOf(PATH_D))
        fallback.requests.forEach { it.tokens() shouldBeLessThanOrEqual oneFilePerBatch.inputTokensPerBatch }
        report.inline.map { it.path } shouldContainExactly listOf(PATH_A, PATH_C)
        report.usage.map { it.provider to it.calls } shouldContainExactly listOf("primary" to 2, "fallback" to 2)
        report.failedBatches.shouldBeEmpty()
    }

    @Test
    fun `also switches to the fallback when the request is rejected`() {
        val primary = ScriptedLlm(fail(LlmFailure.REJECTED))
        val fallback = ScriptedLlm(ok(comment(PATH_A, line = 11)))

        val report =
            completed(
                orchestrator(provider("primary", primary), provider("fallback", fallback))
                    .review(request(smallFile(PATH_A))),
            )

        report.inline.map { it.path } shouldContainExactly listOf(PATH_A)
        fallback.requests.size shouldBe 1
    }

    @Test
    fun `drops only the batch whose answer stays invalid and goes on with the same provider`() {
        val primary = ScriptedLlm(fail(LlmFailure.INVALID_OUTPUT), ok(comment(PATH_B, line = 12)))
        val fallback = ScriptedLlm(ok())

        val report =
            completed(
                orchestrator(provider("primary", primary, oneFilePerBatch), provider("fallback", fallback))
                    .review(request(listOf(bigFile(PATH_A), bigFile(PATH_B)))),
            )

        report.failedBatches.map { Triple(it.paths, it.provider, it.reason) } shouldContainExactly
            listOf(Triple(listOf(PATH_A), "primary", LlmFailure.INVALID_OUTPUT))
        report.inline.map { it.path } shouldContainExactly listOf(PATH_B)
        fallback.requests.shouldBeEmpty()
    }

    @Test
    fun `is unavailable when no provider gives a valid answer`() {
        val primary = ScriptedLlm(fail(LlmFailure.UNAVAILABLE))
        val fallback = ScriptedLlm(fail(LlmFailure.RATE_LIMITED))

        val outcome =
            orchestrator(provider("primary", primary), provider("fallback", fallback))
                .review(request(smallFile(PATH_A)))

        val report = outcome.shouldBeInstanceOf<ReviewOutcome.Unavailable>().report
        report.failedBatches.map { Triple(it.paths, it.provider, it.reason) } shouldContainExactly
            listOf(Triple(listOf(PATH_A), "fallback", LlmFailure.RATE_LIMITED))
        primary.requests.size shouldBe 1
    }

    @Test
    fun `reports the remaining files as failed when the fallback would exceed the daily call limit`() {
        val primary = ScriptedLlm(ok(comment(PATH_A, line = 12)), fail(LlmFailure.RATE_LIMITED))
        val fallback = ScriptedLlm(ok())
        val files = listOf(bigFile(PATH_A), bigFile(PATH_B), bigFile(PATH_C), bigFile(PATH_D))

        val report =
            completed(
                orchestrator(
                    provider("primary", primary, twoFilesPerBatch),
                    provider("fallback", fallback, oneFilePerBatch),
                    limits = QuotaLimits(llmCallsPerDay = 3),
                ).review(request(files)),
            )

        fallback.requests.shouldBeEmpty()
        report.inline.map { it.path } shouldContainExactly listOf(PATH_A)
        report.failedBatches.map { Triple(it.paths, it.provider, it.reason) } shouldContainExactly
            listOf(Triple(listOf(PATH_C, PATH_D), "primary", LlmFailure.RATE_LIMITED))
    }

    @Test
    fun `marks every request with a new boundary`() {
        val llm = ScriptedLlm(ok())
        var draws = 0
        val orchestrator =
            ReviewOrchestrator(
                listOf(provider("primary", llm, oneFilePerBatch)),
                QuotaPolicy(QuotaLimits(), clock),
                boundaries = { "b0undary" + ++draws },
            )

        orchestrator.review(request(listOf(bigFile(PATH_A), bigFile(PATH_B))))

        llm.requests.map {
            it.prompt.user
                .substringAfter("<<<DIFF_")
                .substringBefore("\n")
        } shouldContainExactly
            listOf("b0undary1", "b0undary2")
    }

    @Test
    fun `makes no model call when no file is left to review`() {
        val llm = ScriptedLlm(ok())
        val files =
            listOf(
                DiffFile("README.md", FileStatus.MODIFIED, "@@ -1,0 +1,1 @@\n+More docs."),
                DiffFile("src/main/kotlin/Odd.kt", FileStatus.MODIFIED, "no hunk header in this patch"),
            )

        val report = completed(orchestrator(provider("primary", llm)).review(request(files)))

        llm.requests.shouldBeEmpty()
        report.skipped shouldContainExactly
            listOf(
                SkippedFile("README.md", SkipReason.DOCUMENTATION),
                SkippedFile("src/main/kotlin/Odd.kt", SkipReason.NO_PATCH),
            )
        report.inline.shouldBeEmpty()
    }

    @Test
    fun `leaves out findings already published on the pull request`() {
        val answer = ok(comment(PATH_A, line = 11))
        val first = completed(orchestrator(provider("primary", ScriptedLlm(answer))).review(request(smallFile(PATH_A))))
        val published = first.inline.map { it.fingerprint }.toSet()

        val second =
            completed(
                orchestrator(provider("primary", ScriptedLlm(answer)))
                    .review(request(smallFile(PATH_A)).copy(alreadyPublished = published)),
            )

        second.inline.shouldBeEmpty()
    }

    @Test
    fun `keeps at most maxComments inline and lists the others in the summary`() {
        val llm =
            ScriptedLlm(
                ok(
                    comment(PATH_A, line = 11, severity = Severity.MINOR),
                    comment(PATH_A, line = 12, severity = Severity.MAJOR),
                    comment(PATH_A, line = 99, severity = Severity.MAJOR),
                ),
            )

        val report =
            completed(
                orchestrator(provider("primary", llm))
                    .review(request(smallFile(PATH_A)).copy(settings = ReviewSettings(maxComments = 1))),
            )

        report.inline.map { it.line } shouldContainExactly listOf(12)
        report.summary.map { it.line } shouldContainExactly listOf(99, 11)
    }

    private fun orchestrator(
        vararg providers: ReviewProvider,
        limits: QuotaLimits = QuotaLimits(),
    ) = ReviewOrchestrator(providers.toList(), QuotaPolicy(limits, clock))

    private fun provider(
        name: String,
        llm: LlmPort,
        budget: ProviderBudget = twoFilesPerBatch,
        tokensPerDay: Int = 1_000_000,
    ) = ReviewProvider(name, llm, budget, tokensPerDay)

    private fun request(
        file: DiffFile,
        usage: DailyUsage = DailyUsage(0, 0),
        providerTokens: Map<String, Int> = emptyMap(),
    ) = request(listOf(file), usage, providerTokens)

    private fun request(
        files: List<DiffFile>,
        usage: DailyUsage = DailyUsage(0, 0),
        providerTokens: Map<String, Int> = emptyMap(),
    ) = ReviewRequest(pr, files, usage = usage, providerTokensToday = providerTokens)

    private fun completed(outcome: ReviewOutcome): ReviewReport =
        outcome.shouldBeInstanceOf<ReviewOutcome.Completed>().report

    /** A file whose added lines are numbered from 10. */
    private fun diffFile(
        path: String,
        vararg added: String,
    ) = DiffFile(
        path,
        FileStatus.MODIFIED,
        (listOf("@@ -10,0 +10,${added.size} @@") + added.map { "+$it" }).joinToString("\n"),
    )

    private fun smallFile(path: String) = diffFile(path, "fun a() = 1", "fun b() = 2", "fun c() = 3")

    /** A few hundred tokens: one fits in [oneFilePerBatch], two fit in [twoFilesPerBatch]. */
    private fun bigFile(path: String) = diffFile(path, *Array(20) { "val value$it = \"" + "v".repeat(70) + "\"" })

    private fun comment(
        path: String,
        line: Int,
        severity: Severity = Severity.MAJOR,
    ) = ModelComment(
        path = path,
        startLine = null,
        line = line,
        severity = severity,
        category = Category.BUG,
        title = "Problem on line $line",
        body = "Explained.",
        suggestion = null,
        confidence = 0.9,
    )

    private fun ok(
        vararg comments: ModelComment,
        summary: String = "Reviewed.",
    ): (String) -> LlmResult =
        { provider -> LlmResult.Success(ModelReview(summary, comments.toList()), provider, MODEL, 100, 20) }

    private fun fail(reason: LlmFailure): (String) -> LlmResult =
        { provider -> LlmResult.Failure(reason, provider, "failed") }

    /** Answers with the given results in order, then repeats the last one; records every request. */
    private class ScriptedLlm(
        private vararg val answers: (String) -> LlmResult,
    ) : LlmPort {
        val requests = mutableListOf<LlmRequest>()

        override fun review(request: LlmRequest): LlmResult {
            requests += request
            return answers[minOf(requests.size, answers.size) - 1]("scripted")
        }
    }

    private fun LlmRequest.tokens() = TokenEstimator.estimate(prompt.system) + TokenEstimator.estimate(prompt.user)

    private fun LlmRequest.paths() = FILE_HEADER.findAll(prompt.user).map { it.groupValues[1] }.toList()

    private companion object {
        const val MODEL = "model-x"
        const val SLACK = 50
        const val PATH_A = "src/main/kotlin/A.kt"
        const val PATH_B = "src/main/kotlin/B.kt"
        const val PATH_C = "src/main/kotlin/C.kt"
        const val PATH_D = "src/main/kotlin/D.kt"
        val FILE_HEADER = Regex("""=== File: (\S+) \(""")
    }
}
