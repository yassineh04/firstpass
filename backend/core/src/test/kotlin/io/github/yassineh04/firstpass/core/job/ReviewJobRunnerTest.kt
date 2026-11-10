package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.github.CheckConclusion
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.InlineComment
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.publish.CheckRunTexts
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaNotice
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.settings.ProviderPreference
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.RunStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewJobRunnerTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val github = FakeGitHub().apply { files = listOf(DiffFile(PATH, FileStatus.MODIFIED, PATCH)) }
    private val stores = MemoryStores()
    private val called = mutableListOf<String>()
    private var answer: (String) -> LlmResult = { provider -> success(provider, listOf(bug())) }

    private fun provider(name: String) =
        ReviewProvider(
            name,
            LlmPort {
                called += name
                answer(name)
            },
            ProviderBudget(20_000, 6),
            Int.MAX_VALUE,
        )

    private fun runner(quota: QuotaPolicy = QuotaPolicy(QuotaLimits(), clock)) =
        ReviewJobRunner(
            github,
            listOf(provider("gemini"), provider("groq")),
            stores,
            stores,
            stores,
            quota,
            clock,
            URL,
        ) {
            "b0undary1"
        }

    private fun context(
        settings: ReviewSettings = ReviewSettings(),
        state: PullRequestState = PullRequestState(repoId = 7, number = 3),
    ) = ReviewContext(
        reviewJob(),
        pullRequestDetails(),
        settings,
        emptyList(),
        state,
        checkRunId = 100,
        startedAt = now,
    )

    @Test
    fun `publishes one COMMENT review with the inline finding and the summary, and concludes neutral`() {
        runner().run(context()) shouldBe RunStatus.PUBLISHED

        val review = github.reviews.single()
        review.commitId shouldBe "head1"
        review.body shouldStartWith "### FirstPass review"
        review.body shouldContain "Found 1 issue: 1 major."
        val comment = review.comments.single()
        comment.copy(body = "") shouldBe InlineComment(PATH, line = 11, startLine = null, body = "")
        comment.body shouldStartWith "**Major · Bug**: Crash when b is null"
        comment.body shouldContain "gemini-model"
        github.checkRunUpdates.single().first shouldBe 100
        github.checkRunUpdates
            .single()
            .second.state shouldBe CheckRunState.Completed(CheckConclusion.NEUTRAL)
        github.checkRunUpdates
            .single()
            .second.text.title shouldBe "Found 1 issue"

        val (run, findings) = stores.runs.single()
        run.status shouldBe RunStatus.PUBLISHED
        run.runId shouldBe "delivery-1"
        run.receivedAt shouldBe RECEIVED_AT
        run.startedAt shouldBe now
        run.findingsPosted shouldBe 1
        run.findingsSummarized shouldBe 0
        val state = stores.states.getValue(7L to 3)
        state.lastReviewedSha shouldBe "head1"
        state.fingerprints shouldBe setOf(findings.single().finding.fingerprint)
        stores.consumption
            .single()
            .second
            .single()
            .calls shouldBe 1
    }

    @Test
    fun `publishes a summary on the first review even without findings, and concludes success`() {
        answer = { provider -> success(provider, emptyList()) }

        runner().run(context()) shouldBe RunStatus.PUBLISHED

        github.reviews.single().body shouldContain "No issues found in the changed lines."
        github.checkRunUpdates.single().second shouldBe
            CreatedCheckRun(
                "",
                CheckRunState.Completed(CheckConclusion.SUCCESS),
                CheckRunTexts.completed(emptyList(), ReviewLanguage.EN),
            )
    }

    @Test
    fun `stays quiet on a later push that finds nothing new, and only concludes the check run`() {
        answer = { provider -> success(provider, emptyList()) }

        runner().run(context(state = PullRequestState(7, 3, lastReviewedSha = "head0"))) shouldBe RunStatus.NOTHING_NEW

        github.reviews.shouldBeEmpty()
        github.checkRunUpdates
            .single()
            .second.state shouldBe CheckRunState.Completed(CheckConclusion.SUCCESS)
        stores.states.getValue(7L to 3).lastReviewedSha shouldBe "head1"
    }

    @Test
    fun `never repeats a finding already published on the pull request`() {
        runner().run(context())
        val published = stores.states.getValue(7L to 3)
        github.reviews.clear()
        github.checkRunUpdates.clear()

        runner().run(context(state = published.copy(lastReviewedSha = "head0"))) shouldBe RunStatus.NOTHING_NEW

        github.reviews.shouldBeEmpty()
    }

    @Test
    fun `moves the inline comments into the summary when GitHub refuses them`() {
        github.reviewAnswers.addAll(listOf(false, true))

        runner().run(context()) shouldBe RunStatus.PUBLISHED

        github.reviews.size shouldBe 2
        github.reviews
            .last()
            .comments
            .shouldBeEmpty()
        github.reviews.last().body shouldContain
            "<details><summary>Major · Bug · <code>$PATH:11</code> · Crash when b is null</summary>"
        stores.runs
            .single()
            .first.findingsPosted shouldBe 0
        stores.runs
            .single()
            .first.findingsSummarized shouldBe 1
    }

    @Test
    fun `records a review GitHub refuses even without inline comments, and leaves the head unreviewed`() {
        github.reviewAnswers.addAll(listOf(false, false))

        runner().run(context()) shouldBe RunStatus.PUBLISH_REJECTED

        stores.states[7L to 3]?.lastReviewedSha.shouldBeNull()
    }

    @Test
    fun `publishes nothing when a newer push arrived during the review`() {
        github.pullRequest = pullRequestDetails(headSha = "head2")

        runner().run(context()) shouldBe RunStatus.OUTDATED

        github.reviews.shouldBeEmpty()
        github.checkRunUpdates.single().second shouldBe
            CreatedCheckRun(
                "",
                CheckRunState.Completed(CheckConclusion.NEUTRAL),
                CheckRunTexts.outdated(ReviewLanguage.EN),
            )
        stores.states[7L to 3]?.lastReviewedSha.shouldBeNull()
        stores.consumption
            .single()
            .second
            .single()
            .calls shouldBe 1
    }

    @Test
    fun `reports an unavailable review on the check run and leaves the head unreviewed for the next push`() {
        answer = { provider -> LlmResult.Failure(LlmFailure.UNAVAILABLE, provider, "down") }

        runner().run(context()) shouldBe RunStatus.UNAVAILABLE

        github.reviews.shouldBeEmpty()
        github.checkRunUpdates.single().second shouldBe
            CreatedCheckRun(
                "",
                CheckRunState.Completed(CheckConclusion.NEUTRAL),
                CheckRunTexts.unavailable(ReviewLanguage.EN),
            )
        stores.states[7L to 3]?.lastReviewedSha.shouldBeNull()
    }

    @Test
    fun `completes the started check run with the quota notice when today's model calls are used up`() {
        stores.llmCallsByDay["2026-11-08"] = 300

        runner().run(context()) shouldBe RunStatus.QUOTA_REACHED

        val notice = QuotaNotice.comment(QuotaReason.GLOBAL_LLM_CALLS, ReviewLanguage.EN)
        github.comments shouldContainExactly listOf(notice)
        github.checkRunUpdates.single() shouldBe
            (
                100L to
                    CreatedCheckRun(
                        "",
                        CheckRunState.Completed(CheckConclusion.NEUTRAL),
                        CheckRunText(QuotaNotice.checkRunTitle(ReviewLanguage.EN), notice),
                    )
            )
        github.checkRuns.shouldBeEmpty()
        called.shouldBeEmpty()
        stores.consumption.shouldBeEmpty()
    }

    @Test
    fun `asks the provider the repository prefers first`() {
        runner().run(context(settings = ReviewSettings(provider = ProviderPreference.GROQ)))

        called shouldContainExactly listOf("groq")
        called shouldNotContain "gemini"
    }

    @Test
    fun `keeps the 500 most recent fingerprints of a pull request`() {
        val old = (1..499).map { "old-$it" }.toSet()

        runner().run(context(state = PullRequestState(7, 3, fingerprints = old)))

        val kept = stores.states.getValue(7L to 3).fingerprints
        kept.size shouldBe 500
        kept.first() shouldBe "old-1"
        runner().run(context(state = PullRequestState(7, 3, fingerprints = old + "old-500")))
        stores.states
            .getValue(7L to 3)
            .fingerprints
            .first() shouldBe "old-2"
    }

    private fun success(
        provider: String,
        comments: List<ModelComment>,
    ) = LlmResult.Success(ModelReview("Reviewed.", comments), provider, "$provider-model", 100, 20)

    private fun bug() =
        ModelComment(PATH, null, 11, Severity.MAJOR, Category.BUG, "Crash when b is null", "Explained.", null, 0.9)

    private companion object {
        const val URL = "https://example.org/firstpass"
        const val PATH = "src/main/kotlin/Lookup.kt"
        const val PATCH = "@@ -10,0 +10,3 @@\n+val a = lookup()\n+val b = a!!\n+val c = b.name"
    }
}
