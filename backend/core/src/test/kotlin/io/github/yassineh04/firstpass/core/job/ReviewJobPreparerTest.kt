package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.github.CheckConclusion
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.publish.CheckRunTexts
import io.github.yassineh04.firstpass.core.publish.Notices
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaNotice
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.settings.ConfigIssue
import io.github.yassineh04.firstpass.core.settings.ConfigProblem
import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ReviewJobPreparerTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private val today = "2026-11-08"

    private inner class Fixture {
        val github = FakeGitHub()
        val stores = MemoryStores()
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        val preparer =
            ReviewJobPreparer(
                github,
                stores,
                stores,
                stores,
                stores,
                QuotaPolicy(QuotaLimits(reviewsPerInstallationPerDay = 2), clock),
                clock,
            )

        fun prepare() = preparer.prepare(reviewJob())
    }

    @Test
    fun `starts the review with the merged settings, a reserved quota and a check run in progress`() {
        val fixture = Fixture()
        fixture.stores.dashboardSettings[7] = SettingsOverrides(maxComments = 5, language = ReviewLanguage.EN)
        val issue = ConfigIssue("colour", ConfigProblem.UNKNOWN_SETTING)
        fixture.github.config = ParsedConfig(SettingsOverrides(language = ReviewLanguage.FR), listOf(issue))

        val context = fixture.prepare().shouldBeInstanceOf<Preparation.Ready>().context

        context.settings.language shouldBe ReviewLanguage.FR
        context.settings.maxComments shouldBe 5
        context.configIssues shouldContainExactly listOf(issue)
        context.pullRequest shouldBe pullRequestDetails()
        context.state shouldBe PullRequestState(repoId = 7, number = 3)
        context.checkRunId shouldBe 100
        context.startedAt shouldBe now
        fixture.github.calls shouldContain "repositoryConfig:main"
        fixture.github.checkRuns shouldContainExactly
            listOf(CreatedCheckRun("head1", CheckRunState.InProgress, CheckRunTexts.inProgress(ReviewLanguage.FR)))
        fixture.stores.reviewsByDay shouldBe mapOf((42L to today) to 1)
    }

    @Test
    fun `stops before any call to GitHub when the installation, the repository or the pull request state say so`() {
        val cases: List<Pair<StopReason, (MemoryStores) -> Unit>> =
            listOf(
                StopReason.INSTALLATION_INACTIVE to { it.installations.clear() },
                StopReason.INSTALLATION_INACTIVE to
                    { it.installations[42] = it.installations.getValue(42).copy(suspended = true) },
                StopReason.REPOSITORY_DISABLED to { it.repositories.clear() },
                StopReason.REPOSITORY_DISABLED to
                    { it.repositories[7] = it.repositories.getValue(7).copy(enabled = false) },
                StopReason.REPOSITORY_DISABLED to {
                    it.repositories[7] = it.repositories.getValue(7).copy(repository = SANDBOX.copy(private = true))
                },
                StopReason.PAUSED to { it.states[7L to 3] = PullRequestState(7, 3, paused = true) },
                StopReason.ALREADY_REVIEWED to
                    { it.states[7L to 3] = PullRequestState(7, 3, lastReviewedSha = "head1") },
            )

        cases.forEachIndexed { index, (reason, setUp) ->
            val fixture = Fixture()
            setUp(fixture.stores)

            withClue("case $index") {
                fixture.prepare() shouldBe Preparation.Stopped(reason)
                fixture.github.calls.shouldBeEmpty()
                fixture.stores.reviewsByDay.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `reviews a private repository once an admin consented`() {
        val fixture = Fixture()
        fixture.stores.repositories[7] =
            fixture.stores.repositories
                .getValue(7)
                .copy(repository = SANDBOX.copy(private = true), consentAt = now)

        fixture.prepare().shouldBeInstanceOf<Preparation.Ready>()
    }

    @Test
    fun `stops when GitHub says the pull request is closed or has a newer head, before any quota or check run`() {
        for ((details, reason) in listOf(
            pullRequestDetails(open = false) to StopReason.CLOSED,
            pullRequestDetails(headSha = "head2") to StopReason.OUTDATED,
        )) {
            val fixture = Fixture()
            fixture.github.pullRequest = details

            fixture.prepare() shouldBe Preparation.Stopped(reason)
            fixture.github.checkRuns.shouldBeEmpty()
            fixture.stores.reviewsByDay.shouldBeEmpty()
        }
    }

    @Test
    fun `stops without a word when the settings turn reviews off, for drafts and for bots`() {
        val cases =
            listOf(
                StopReason.DISABLED_BY_SETTINGS to
                    { f: Fixture -> f.github.config = ParsedConfig(SettingsOverrides(enabled = false), emptyList()) },
                StopReason.DRAFT to { f: Fixture -> f.github.pullRequest = pullRequestDetails(draft = true) },
                StopReason.BOT_AUTHOR to
                    { f: Fixture ->
                        f.github.pullRequest =
                            pullRequestDetails(authorLogin = "dependabot[bot]", authorType = "Bot")
                    },
            )

        for ((reason, setUp) in cases) {
            val fixture = Fixture()
            setUp(fixture)

            fixture.prepare() shouldBe Preparation.Stopped(reason)
            fixture.github.comments.shouldBeEmpty()
            fixture.github.checkRuns.shouldBeEmpty()
            fixture.stores.reviewsByDay.shouldBeEmpty()
        }
    }

    @Test
    fun `explains once why a pull request is too large, then stays silent on the next pushes`() {
        val fixture = Fixture()
        fixture.github.pullRequest = pullRequestDetails(additions = 1990, deletions = 20)

        fixture.prepare() shouldBe Preparation.Stopped(StopReason.TOO_LARGE)
        fixture.prepare() shouldBe Preparation.Stopped(StopReason.TOO_LARGE)

        fixture.github.comments shouldContainExactly listOf(Notices.tooLarge(2010, 1500, ReviewLanguage.EN))
        fixture.stores.states
            .getValue(7L to 3)
            .tooLargeNoticePosted shouldBe true
        fixture.github.checkRuns.shouldBeEmpty()
        fixture.stores.reviewsByDay.shouldBeEmpty()
    }

    @Test
    fun `refuses the review when the daily quota is used up, with one notice a day and a neutral check run`() {
        val fixture = Fixture()
        fixture.stores.reviewsByDay[42L to today] = 2
        val notice = QuotaNotice.comment(QuotaReason.INSTALLATION_REVIEWS, ReviewLanguage.EN)
        val checkRun =
            CreatedCheckRun(
                "head1",
                CheckRunState.Completed(CheckConclusion.NEUTRAL),
                CheckRunText(QuotaNotice.checkRunTitle(ReviewLanguage.EN), notice),
            )

        fixture.prepare() shouldBe Preparation.Stopped(StopReason.QUOTA_REACHED)
        fixture.prepare() shouldBe Preparation.Stopped(StopReason.QUOTA_REACHED)

        fixture.github.comments shouldContainExactly listOf(notice)
        fixture.github.checkRuns shouldContainExactly listOf(checkRun, checkRun)
        fixture.stores.states
            .getValue(7L to 3)
            .lastQuotaNoticeDay shouldBe LocalDate.parse(today)
    }
}
