package io.github.yassineh04.firstpass.core.diff

/** Change status of a file in a pull request. */
enum class FileStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    COPIED,
}

/**
 * One changed file of a pull request.
 *
 * [patch] holds the unified diff hunks of the file without the git headers, like the `patch`
 * field of GitHub's "list pull request files" API. It is null when there is nothing to show:
 * binary file, file too large for GitHub, or rename without content change.
 */
data class DiffFile(
    val path: String,
    val status: FileStatus,
    val patch: String?,
    val previousPath: String? = null,
)
