package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.GitHubPort
import io.github.yassineh04.firstpass.core.github.InlineComment
import io.github.yassineh04.firstpass.core.github.PullRequestDetails
import io.github.yassineh04.firstpass.core.github.ReviewSubmission
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.publish.CheckRunTexts
import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.settings.RepositoryConfig
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import tools.jackson.databind.JsonNode
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.net.URLEncoder
import java.util.Base64

/** The GitHub calls of a review job, made as the installation (design 7.1, steps 8 to 18). */
class GitHubRestClient(
    private val http: GitHubHttp,
    private val tokens: InstallationTokens,
) : GitHubPort {
    override fun pullRequest(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): PullRequestDetails {
        val json =
            expect(
                http.get("${base(repository)}/pulls/$number", tokens.authorization(installationId)),
                HTTP_OK,
                "GET pull request",
            ).json()
        return PullRequestDetails(
            number = json.int("number"),
            title = json.text("title"),
            body = json.textOrNull("body"),
            open = json.text("state") == "open",
            draft = json.flag("draft"),
            authorLogin = json.text("user.login"),
            authorType = json.text("user.type"),
            additions = json.int("additions"),
            deletions = json.int("deletions"),
            changedFiles = json.int("changed_files"),
            headSha = json.text("head.sha"),
            baseRef = json.text("base.ref"),
            baseSha = json.text("base.sha"),
        )
    }

    /** A missing file is no settings; a folder, a large file or broken content is reported as an invalid file. */
    override fun repositoryConfig(
        installationId: Long,
        repository: RepositoryRef,
        ref: String,
    ): ParsedConfig? {
        val file = RepositoryConfig.FILE_NAME
        val query = URLEncoder.encode(ref, Charsets.UTF_8)
        val response = http.get("${base(repository)}/contents/$file?ref=$query", tokens.authorization(installationId))
        if (response.status == HTTP_NOT_FOUND) return null
        val json = expect(response, HTTP_OK, "GET $file").json()
        val bytes = json.textOrNull("content")?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
        // A folder answers with a JSON array: the type is checked first, before reading the size.
        return when {
            json.textOrNull("type") != "file" -> RepositoryConfig.unreadable("$file is not a file")
            !json.isSmallFile() -> RepositoryConfig.unreadable("the file is larger than 64 KB")
            bytes == null -> RepositoryConfig.unreadable("the file content could not be decoded")
            else -> RepositoryConfigReader.read(String(bytes, Charsets.UTF_8))
        }
    }

    override fun createCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        headSha: String,
        state: CheckRunState,
        text: CheckRunText,
    ): Long {
        val body = mapOf("name" to CheckRunTexts.NAME, "head_sha" to headSha) + checkRunFields(state, text)
        val response =
            http.send(
                "POST",
                "${base(repository)}/check-runs",
                tokens.authorization(installationId),
                JSON.writeValueAsString(body),
            )
        return expect(response, HTTP_CREATED, "Creating the check run").json().long("id")
    }

    override fun updateCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        checkRunId: Long,
        state: CheckRunState,
        text: CheckRunText,
    ) {
        val body = JSON.writeValueAsString(checkRunFields(state, text))
        expect(
            http.send(
                "PATCH",
                "${base(repository)}/check-runs/$checkRunId",
                tokens.authorization(installationId),
                body,
            ),
            HTTP_OK,
            "Updating the check run",
        )
    }

    override fun postComment(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        body: String,
    ) {
        val json = JSON.writeValueAsString(mapOf("body" to body.take(MAX_BODY)))
        expect(
            http.send(
                "POST",
                "${base(repository)}/issues/$number/comments",
                tokens.authorization(installationId),
                json,
            ),
            HTTP_CREATED,
            "Posting a comment",
        )
    }

    override fun pullRequestFiles(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): List<DiffFile> =
        http
            .getAll(
                "${base(repository)}/pulls/$number/files?per_page=$PAGE_SIZE",
                tokens.authorization(installationId),
            ).map {
                DiffFile(
                    path = it.text("filename"),
                    status = STATUSES[it.text("status")] ?: FileStatus.MODIFIED,
                    patch = it.textOrNull("patch"),
                    previousPath = it.textOrNull("previous_filename"),
                )
            }

    /**
     * The event is always COMMENT (FR-10). A 422 means GitHub refused the comments: the answer is
     * false, so that the caller can publish again without them.
     */
    override fun publishReview(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        review: ReviewSubmission,
    ): Boolean {
        val body =
            mapOf(
                "commit_id" to review.commitId,
                "body" to review.body.take(MAX_BODY),
                "event" to "COMMENT",
                "comments" to review.comments.map(::comment),
            )
        val response =
            http.send(
                "POST",
                "${base(repository)}/pulls/$number/reviews",
                tokens.authorization(installationId),
                JSON.writeValueAsString(body),
            )
        return when (response.status) {
            HTTP_OK -> true
            HTTP_UNPROCESSABLE -> false
            else -> throw response.failure("Publishing the review")
        }
    }

    private fun comment(comment: InlineComment): Map<String, Any> {
        val fields =
            mapOf(
                "path" to comment.path,
                "line" to comment.line,
                "side" to RIGHT,
                "body" to comment.body.take(MAX_BODY),
            )
        val range = comment.startLine?.let { mapOf("start_line" to it, "start_side" to RIGHT) }.orEmpty()
        return fields + range
    }

    private fun checkRunFields(
        state: CheckRunState,
        text: CheckRunText,
    ): Map<String, Any> {
        val status =
            when (state) {
                CheckRunState.InProgress -> {
                    mapOf("status" to "in_progress")
                }

                is CheckRunState.Completed -> {
                    mapOf(
                        "status" to "completed",
                        "conclusion" to state.conclusion.name.lowercase(),
                    )
                }
            }
        return status + ("output" to mapOf("title" to text.title, "summary" to text.summary.take(MAX_SUMMARY)))
    }

    private fun base(repository: RepositoryRef) = "/repos/${repository.fullName}"

    private fun expect(
        response: GitHubResponse,
        status: Int,
        call: String,
    ): GitHubResponse = if (response.status == status) response else throw response.failure(call)

    private companion object {
        const val PAGE_SIZE = 100
        const val RIGHT = "RIGHT"
        const val HTTP_UNPROCESSABLE = 422

        /** Documented for check run summaries. */
        const val MAX_SUMMARY = 65_535

        /** Not documented for comments and reviews: a conservative cap under the check run limit. */
        const val MAX_BODY = 65_000

        val STATUSES =
            mapOf(
                "added" to FileStatus.ADDED,
                "removed" to FileStatus.REMOVED,
                "modified" to FileStatus.MODIFIED,
                "changed" to FileStatus.MODIFIED,
                "unchanged" to FileStatus.MODIFIED,
                "renamed" to FileStatus.RENAMED,
                "copied" to FileStatus.COPIED,
            )
    }
}

private const val MAX_CONFIG_BYTES = 64 * 1024

/** GitHub sends files over 1 MB without content (encoding "none"); settings never need 64 KB. */
private fun JsonNode.isSmallFile() = long("size") <= MAX_CONFIG_BYTES && textOrNull("encoding") == "base64"
