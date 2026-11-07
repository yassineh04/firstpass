package io.github.yassineh04.firstpass.core.diff

/**
 * Splits a complete git diff (`git diff` or `git format-patch` output) into changed files.
 *
 * Each file starts at a `diff --git` line; anything before the first one (such as the email
 * headers of `git format-patch`) is ignored. The patch of each file is the text from its first
 * hunk header to the next file, the same shape as the `patch` field of the GitHub API.
 */
object UnifiedDiffParser {
    private const val FILE_HEADER = "diff --git "

    fun parse(diff: String): List<DiffFile> {
        val lines = diff.lines().map { it.removeSuffix("\r") }
        val starts = lines.indices.filter { lines[it].startsWith(FILE_HEADER) }
        return starts.mapIndexed { position, start ->
            val end = starts.getOrNull(position + 1) ?: lines.size
            parseFile(lines.subList(start, end))
        }
    }

    private fun parseFile(block: List<String>): DiffFile {
        val firstHunk = block.indexOfFirst { it.startsWith("@@") }
        val headers = FileHeaders.parse(if (firstHunk == -1) block else block.subList(0, firstHunk))
        val patch =
            if (firstHunk == -1 || headers.binary) {
                null
            } else {
                block.subList(firstHunk, block.size).joinToString("\n").trimEnd('\n')
            }
        return DiffFile(
            path = headers.path,
            status = headers.status,
            patch = patch,
            previousPath = headers.previousPath,
        )
    }

    /** The git extended headers of one file, between `diff --git` and the first hunk. */
    private data class FileHeaders(
        val path: String,
        val status: FileStatus,
        val previousPath: String?,
        val binary: Boolean,
    ) {
        companion object {
            fun parse(lines: List<String>): FileHeaders {
                fun valueOf(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

                val renameFrom = valueOf("rename from ")
                val copyFrom = valueOf("copy from ")
                val newPath = valueOf("+++ ")?.let { sidePath(it, "b/") }
                val oldPath = valueOf("--- ")?.let { sidePath(it, "a/") }
                val headerPath =
                    lines
                        .first()
                        .removePrefix(FILE_HEADER)
                        .substringAfterLast(" b/")
                        .trim('"')
                return FileHeaders(
                    path = valueOf("rename to ") ?: valueOf("copy to ") ?: newPath ?: oldPath ?: headerPath,
                    status = statusOf(lines, renamed = renameFrom != null, copied = copyFrom != null),
                    previousPath = renameFrom ?: copyFrom,
                    binary = lines.any { it.startsWith("Binary files ") || it == "GIT binary patch" },
                )
            }

            private fun statusOf(
                lines: List<String>,
                renamed: Boolean,
                copied: Boolean,
            ): FileStatus =
                when {
                    lines.any { it.startsWith("new file mode") } -> FileStatus.ADDED
                    lines.any { it.startsWith("deleted file mode") } -> FileStatus.REMOVED
                    renamed -> FileStatus.RENAMED
                    copied -> FileStatus.COPIED
                    else -> FileStatus.MODIFIED
                }

            /** Path of one side of the diff, or null for `/dev/null` (file absent on that side). */
            private fun sidePath(
                value: String,
                prefix: String,
            ): String? {
                val path = value.substringBefore('\t').trim('"')
                return if (path == "/dev/null") null else path.removePrefix(prefix)
            }
        }
    }
}
