package io.github.yassineh04.firstpass.core.filter

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.settings.ReviewSettings

/** Kind of a changed file. Reviewable kinds are declared in review priority order. */
enum class FileKind {
    SOURCE,
    TEST,
    CONFIG,
    DOCUMENTATION,
}

/** Why a changed file is not sent to the model; listed in the review summary. */
enum class SkipReason {
    DELETED,
    NO_PATCH,
    SENSITIVE,
    LOCKFILE,
    BUILD_OUTPUT,
    MINIFIED,
    BINARY,
    EXCLUDED_BY_CONFIG,
    NOT_INCLUDED,
    DOCUMENTATION,
}

data class ReviewableFile(
    val file: DiffFile,
    val kind: FileKind,
)

data class SkippedFile(
    val path: String,
    val reason: SkipReason,
)

data class FilterResult(
    val reviewable: List<ReviewableFile>,
    val skipped: List<SkippedFile>,
)

/**
 * Decides which changed files are reviewed. Checks run in this order: deleted file, missing
 * patch, built-in exclusions, repository `exclude`, repository `include`, documentation.
 * Reviewable files come back sorted by priority: source code, then tests, then configuration.
 */
class FileFilter(
    settings: ReviewSettings,
) {
    private val includes = settings.include.map(::Glob)
    private val excludes = settings.exclude.map(::Glob)

    fun filter(files: List<DiffFile>): FilterResult {
        val reviewable = mutableListOf<ReviewableFile>()
        val skipped = mutableListOf<SkippedFile>()
        for (file in files) {
            val kind = FileClassifier.kindOf(file.path)
            when (val reason = skipReason(file, kind)) {
                null -> reviewable += ReviewableFile(file, kind)
                else -> skipped += SkippedFile(file.path, reason)
            }
        }
        return FilterResult(reviewable.sortedBy { it.kind }, skipped)
    }

    private fun skipReason(
        file: DiffFile,
        kind: FileKind,
    ): SkipReason? =
        when {
            file.status == FileStatus.REMOVED -> SkipReason.DELETED
            file.patch.isNullOrEmpty() -> SkipReason.NO_PATCH
            else -> DefaultExclusions.reasonFor(file.path) ?: repositoryReason(file.path, kind)
        }

    private fun repositoryReason(
        path: String,
        kind: FileKind,
    ): SkipReason? =
        when {
            excludes.any { it.matches(path) } -> SkipReason.EXCLUDED_BY_CONFIG
            includes.isNotEmpty() && includes.none { it.matches(path) } -> SkipReason.NOT_INCLUDED
            kind == FileKind.DOCUMENTATION -> SkipReason.DOCUMENTATION
            else -> null
        }
}
