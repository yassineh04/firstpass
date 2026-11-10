package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.GitHubPort
import io.github.yassineh04.firstpass.core.github.PullRequestDetails
import io.github.yassineh04.firstpass.core.github.ReviewSubmission
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.store.DashboardSettingsStore
import io.github.yassineh04.firstpass.core.store.FindingRecord
import io.github.yassineh04.firstpass.core.store.InstallationRecord
import io.github.yassineh04.firstpass.core.store.InstallationStore
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.PullRequestStateStore
import io.github.yassineh04.firstpass.core.store.RepositoryRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunStore
import io.github.yassineh04.firstpass.core.store.UsageStore
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import java.time.Instant

internal val SANDBOX = RepositoryRef(id = 7, fullName = "octo/sandbox", private = false)

/** When GitHub's webhook for the test jobs arrived: a little before the jobs run. */
internal val RECEIVED_AT: Instant = Instant.parse("2026-11-08T09:59:58.250Z")

internal fun reviewJob(
    headSha: String = "head1",
    trigger: ReviewTrigger = ReviewTrigger.OPENED,
) = Job.Review(
    "delivery-1",
    RECEIVED_AT,
    installationId = 42,
    SANDBOX,
    pullRequest = 3,
    headSha = headSha,
    baseSha = "base1",
    trigger,
)

internal fun pullRequestDetails(
    open: Boolean = true,
    draft: Boolean = false,
    authorLogin: String = "octocat",
    authorType: String = "User",
    additions: Int = 10,
    deletions: Int = 5,
    headSha: String = "head1",
) = PullRequestDetails(
    number = 3,
    title = "Add a user lookup",
    body = "Looks users up by id.",
    open = open,
    draft = draft,
    authorLogin = authorLogin,
    authorType = authorType,
    additions = additions,
    deletions = deletions,
    changedFiles = 2,
    headSha = headSha,
    baseRef = "main",
    baseSha = "base1",
)

internal data class CreatedCheckRun(
    val headSha: String,
    val state: CheckRunState,
    val text: CheckRunText,
)

/** A GitHub that answers from fields and records every call. */
internal class FakeGitHub : GitHubPort {
    var pullRequest = pullRequestDetails()
    var config: ParsedConfig? = null
    val calls = mutableListOf<String>()
    val checkRuns = mutableListOf<CreatedCheckRun>()
    val comments = mutableListOf<String>()
    private var nextCheckRunId = 100L

    override fun pullRequest(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): PullRequestDetails = pullRequest.also { calls += "pullRequest" }

    override fun repositoryConfig(
        installationId: Long,
        repository: RepositoryRef,
        ref: String,
    ): ParsedConfig? = config.also { calls += "repositoryConfig:$ref" }

    override fun createCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        headSha: String,
        state: CheckRunState,
        text: CheckRunText,
    ): Long {
        calls += "createCheckRun"
        checkRuns += CreatedCheckRun(headSha, state, text)
        return nextCheckRunId++
    }

    override fun postComment(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        body: String,
    ) {
        calls += "postComment"
        comments += body
    }

    var files: List<DiffFile> = emptyList()
    val checkRunUpdates = mutableListOf<Pair<Long, CreatedCheckRun>>()
    val reviews = mutableListOf<ReviewSubmission>()

    /** What GitHub answers to each review submission in turn; accepted once the list runs out. */
    val reviewAnswers = ArrayDeque<Boolean>()

    override fun pullRequestFiles(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): List<DiffFile> = files.also { calls += "pullRequestFiles" }

    override fun updateCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        checkRunId: Long,
        state: CheckRunState,
        text: CheckRunText,
    ) {
        calls += "updateCheckRun"
        checkRunUpdates += checkRunId to CreatedCheckRun("", state, text)
    }

    override fun publishReview(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        review: ReviewSubmission,
    ): Boolean {
        calls += "publishReview"
        reviews += review
        return reviewAnswers.removeFirstOrNull() ?: true
    }
}

/** Every store in memory, starting with an active installation and an enabled public repository. */
internal class MemoryStores :
    InstallationStore,
    PullRequestStateStore,
    DashboardSettingsStore,
    UsageStore,
    ReviewRunStore {
    val installations = mutableMapOf(42L to InstallationRecord(42, "octo", "User", suspended = false))
    val repositories = mutableMapOf(7L to RepositoryRecord(42, SANDBOX, enabled = true, consentAt = null))
    val states = mutableMapOf<Pair<Long, Int>, PullRequestState>()
    val dashboardSettings = mutableMapOf<Long, SettingsOverrides>()
    val reviewsByDay = mutableMapOf<Pair<Long, String>, Int>()

    override fun installation(installationId: Long) = installations[installationId]

    override fun repository(
        installationId: Long,
        repoId: Long,
    ) = repositories[repoId]?.takeIf { it.installationId == installationId }

    /** The repositories purged, in order. */
    val purged = mutableListOf<Long>()

    override fun repositories(installationId: Long) = repositories.values.filter { it.installationId == installationId }

    override fun saveInstallation(installation: InstallationRecord) {
        installations[installation.installationId] = installation
    }

    override fun addRepository(repository: RepositoryRecord): Boolean =
        repositories.putIfAbsent(repository.repository.id, repository) == null

    override fun purgeRepository(
        installationId: Long,
        repoId: Long,
    ) {
        if (repositories[repoId]?.installationId == installationId) repositories.remove(repoId)
        states.keys.removeIf { it.first == repoId }
        dashboardSettings.remove(repoId)
        runs.removeIf { it.first.repoId == repoId }
        purged += repoId
    }

    override fun deleteInstallation(installationId: Long) {
        installations.remove(installationId)
    }

    override fun state(
        repoId: Long,
        number: Int,
    ) = states[repoId to number]

    override fun save(state: PullRequestState) {
        states[state.repoId to state.number] = state
    }

    override fun settings(repoId: Long) = dashboardSettings[repoId]

    override fun reserveReview(
        installationId: Long,
        day: String,
        limit: Int,
    ): Boolean {
        val used = reviewsByDay[installationId to day] ?: 0
        if (used >= limit) return false
        reviewsByDay[installationId to day] = used + 1
        return true
    }

    val llmCallsByDay = mutableMapOf<String, Int>()
    val tokensByDay = mutableMapOf<String, Map<String, Int>>()
    val consumption = mutableListOf<Pair<String, List<ProviderUsage>>>()
    val runs = mutableListOf<Pair<ReviewRunRecord, List<FindingRecord>>>()

    override fun globalLlmCalls(day: String) = llmCallsByDay[day] ?: 0

    override fun providerTokens(day: String) = tokensByDay[day] ?: emptyMap()

    override fun addConsumption(
        installationId: Long,
        day: String,
        usage: List<ProviderUsage>,
    ) {
        consumption += day to usage
    }

    override fun save(
        run: ReviewRunRecord,
        findings: List<FindingRecord>,
    ) {
        runs += run to findings
    }
}
