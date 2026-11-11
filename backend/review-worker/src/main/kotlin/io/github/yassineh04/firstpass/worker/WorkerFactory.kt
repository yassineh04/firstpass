package io.github.yassineh04.firstpass.worker

import io.github.yassineh04.firstpass.core.job.InstallationJobHandler
import io.github.yassineh04.firstpass.core.job.JobHandler
import io.github.yassineh04.firstpass.core.job.PullRequestClosedHandler
import io.github.yassineh04.firstpass.core.job.ReviewJobHandler
import io.github.yassineh04.firstpass.core.job.ReviewJobPreparer
import io.github.yassineh04.firstpass.core.job.ReviewJobRunner
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.github.AppCredentials
import io.github.yassineh04.firstpass.github.GitHubAppClient
import io.github.yassineh04.firstpass.github.GitHubHttp
import io.github.yassineh04.firstpass.github.GitHubRestClient
import io.github.yassineh04.firstpass.github.InstallationTokens
import io.github.yassineh04.firstpass.github.PrivateKeys
import io.github.yassineh04.firstpass.llm.ProviderChain
import io.github.yassineh04.firstpass.persistence.DynamoInstallationStore
import io.github.yassineh04.firstpass.persistence.DynamoPullRequestStateStore
import io.github.yassineh04.firstpass.persistence.DynamoReviewRunStore
import io.github.yassineh04.firstpass.persistence.DynamoSettingsStore
import io.github.yassineh04.firstpass.persistence.DynamoUsageStore
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import java.net.URI
import java.nio.file.Path
import java.time.Clock

/** A setting the worker needs is missing; only its name is reported, never a value. */
class MissingSettingException(
    name: String,
) : IllegalStateException(
        "$name is not set: add it to the .env file at the repository root, or set it in the environment.",
    )

/** What the review worker runs, built once per process. */
class Worker(
    /** Handles every job of the queue. */
    val jobs: JobHandler,
    /** Calls GitHub as the app itself, for example to list its installations. */
    val apps: GitHubAppClient,
    /** Records installations and their repositories. */
    val installations: InstallationJobHandler,
)

/**
 * Builds the worker from its settings (design 6 and 7.1): the GitHub App, the review providers,
 * the stores on [table] and the default quotas.
 *
 * - `GITHUB_CLIENT_ID` and `GITHUB_APP_PRIVATE_KEY_PATH` (a PKCS#8 key) identify the app.
 * - `FIRSTPASS_LLM_PROVIDER` chooses the provider: `gemini` (the default, with Groq as fallback
 *   when `GROQ_API_KEY` is set), `groq`, `ollama` or `fake`; see [ProviderChain].
 * - `FIRSTPASS_TRANSPARENCY_URL` is the page linked from every review.
 * - `GITHUB_API_URL` replaces `https://api.github.com`, for tests only.
 */
object WorkerFactory {
    const val DEFAULT_PROVIDER = "gemini"
    const val DEFAULT_TRANSPARENCY_URL = "https://github.com/yassineh04/firstpass#readme"
    private const val GITHUB_API = "https://api.github.com"

    /** [providers] replaces the providers of the settings, for tests only. */
    fun create(
        settings: Map<String, String>,
        table: FirstPassTable,
        clock: Clock = Clock.systemUTC(),
        providers: List<ReviewProvider>? = null,
    ): Worker {
        fun optional(name: String) = settings[name]?.trim()?.takeIf { it.isNotEmpty() }

        fun required(name: String) = optional(name) ?: throw MissingSettingException(name)

        val http = GitHubHttp(URI.create(optional("GITHUB_API_URL") ?: GITHUB_API), clock)
        // In AWS, the key itself comes from SSM; locally, a file path keeps it out of .env.
        val key =
            optional("GITHUB_APP_PRIVATE_KEY")?.let(PrivateKeys::readPkcs8)
                ?: PrivateKeys.read(Path.of(required("GITHUB_APP_PRIVATE_KEY_PATH")))
        val credentials = AppCredentials(required("GITHUB_CLIENT_ID"), key)
        val tokens = InstallationTokens(http, credentials, clock)
        val github = GitHubRestClient(http, tokens)
        val reviewers =
            providers ?: ProviderChain.build(optional("FIRSTPASS_LLM_PROVIDER") ?: DEFAULT_PROVIDER, null, settings)
        val installations = DynamoInstallationStore(table)
        val states = DynamoPullRequestStateStore(table, clock)
        val usage = DynamoUsageStore(table, reviewers.map { it.name })
        val quota = QuotaPolicy(QuotaLimits(), clock)
        val transparency = optional("FIRSTPASS_TRANSPARENCY_URL") ?: DEFAULT_TRANSPARENCY_URL
        val reviews =
            ReviewJobHandler(
                ReviewJobPreparer(github, installations, states, DynamoSettingsStore(table), usage, quota, clock),
                ReviewJobRunner(
                    github,
                    reviewers,
                    states,
                    DynamoReviewRunStore(table),
                    usage,
                    quota,
                    clock,
                    transparency,
                ),
            )
        val installationJobs = InstallationJobHandler(installations)
        return Worker(
            jobs = JobHandler(reviews, installationJobs, PullRequestClosedHandler(states)),
            apps = GitHubAppClient(http, credentials, tokens, clock),
            installations = installationJobs,
        )
    }
}
