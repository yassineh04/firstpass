package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_OK
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Installation access tokens, created with the app's JWT and kept in memory until 5 minutes before
 * they expire (GitHub makes them valid for one hour). Their format may change, so no length is
 * assumed. A token is never printed.
 */
class InstallationTokens(
    private val http: GitHubHttp,
    private val credentials: AppCredentials,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val cache = ConcurrentHashMap<Long, Token>()

    /** The `Authorization` header value for calls made as [installationId]. */
    fun authorization(installationId: Long): String = "Bearer ${token(installationId).value}"

    private fun token(installationId: Long): Token {
        val cached = cache[installationId]
        if (cached != null && clock.instant() < cached.expiresAt.minus(REFRESH_MARGIN)) return cached
        val response =
            http.send(
                "POST",
                "/app/installations/$installationId/access_tokens",
                "Bearer ${AppJwt.create(credentials, clock.instant())}",
                body = "{}",
            )
        if (response.status != HTTP_CREATED) throw response.failure("Creating an installation token")
        val json = response.json()
        return Token(json.text("token"), Instant.parse(json.text("expires_at"))).also { cache[installationId] = it }
    }

    private class Token(
        val value: String,
        val expiresAt: Instant,
    ) {
        override fun toString(): String = "Token(****, expiresAt=$expiresAt)"
    }

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}

data class AppInfo(
    val id: Long,
    val slug: String?,
    val name: String,
)

/** An installation; [account] is the user or organization login, or an enterprise's slug. */
data class InstallationInfo(
    val id: Long,
    val account: String,
)

/** Calls made as the app itself (with its JWT) or as one of its installations. */
class GitHubAppClient(
    private val http: GitHubHttp,
    private val credentials: AppCredentials,
    private val tokens: InstallationTokens,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun app(): AppInfo {
        val response = http.get("/app", appAuthorization())
        if (response.status != HTTP_OK) throw response.failure("GET /app")
        val json = response.json()
        return AppInfo(json.long("id"), json.textOrNull("slug"), json.text("name"))
    }

    fun installations(): List<InstallationInfo> =
        http.getAll("/app/installations?per_page=$PAGE_SIZE", appAuthorization()).map {
            InstallationInfo(it.long("id"), it.textOrNull("account.login") ?: it.text("account.slug"))
        }

    fun installationRepositories(installationId: Long): List<RepositoryRef> =
        http
            .getAll("/installation/repositories?per_page=$PAGE_SIZE", tokens.authorization(installationId)) {
                it.dig("repositories").values().toList()
            }.map(::repositoryOf)

    private fun appAuthorization(): String = "Bearer ${AppJwt.create(credentials, clock.instant())}"

    private companion object {
        const val PAGE_SIZE = 100
    }
}
