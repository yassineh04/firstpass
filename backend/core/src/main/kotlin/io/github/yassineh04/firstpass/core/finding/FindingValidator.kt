package io.github.yassineh04.firstpass.core.finding

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.settings.ReviewSettings

/**
 * Findings of one batch after checks: [anchored] can be published on a diff line, [unanchored]
 * are valid but point outside the diff and go to the review summary, and [rejected] counts the
 * comments dropped for low confidence or low severity.
 */
data class ValidatedFindings(
    val anchored: List<Finding>,
    val unanchored: List<Finding>,
    val rejected: Int,
)

/**
 * Business checks on the comments of the model, which may be wrong or manipulated: the file
 * must belong to the batch, the lines must be commentable, the confidence and the severity
 * must reach their thresholds. The text is cleaned and unsafe code suggestions are dropped
 * before anything is published.
 */
class FindingValidator(
    private val settings: ReviewSettings,
) {
    fun validate(
        comments: List<ModelComment>,
        files: List<AnnotatedFile>,
    ): ValidatedFindings {
        val filesByPath = files.associateBy { it.file.path }
        val anchored = mutableListOf<Finding>()
        val unanchored = mutableListOf<Finding>()
        var rejected = 0
        for (comment in comments) {
            if (comment.confidence < MIN_CONFIDENCE || comment.severity.ordinal > settings.severityThreshold.ordinal) {
                rejected++
                continue
            }
            val file = filesByPath[comment.path]
            // A range that starts and ends on the same line is a single-line comment.
            val startLine = comment.startLine?.takeIf { it != comment.line }
            val finding = toFinding(comment, startLine, file)
            val anchorable = file != null && isAnchorable(file, startLine, comment.line)
            if (anchorable) anchored += finding else unanchored += finding
        }
        return ValidatedFindings(anchored, unanchored, rejected)
    }

    private fun isAnchorable(
        file: AnnotatedFile,
        startLine: Int?,
        line: Int,
    ): Boolean =
        if (startLine == null) {
            file.isCommentable(line, settings.commentOn)
        } else {
            file.isCommentableRange(startLine, line, settings.commentOn)
        }

    private fun toFinding(
        comment: ModelComment,
        startLine: Int?,
        file: AnnotatedFile?,
    ): Finding {
        val title = TextSanitizer.clean(comment.title, MAX_TITLE)
        val lineContent = file?.contentAt(comment.line).orEmpty()
        return Finding(
            path = comment.path,
            startLine = startLine,
            line = comment.line,
            severity = comment.severity,
            category = comment.category,
            title = title,
            body = TextSanitizer.clean(comment.body, MAX_BODY),
            suggestion =
                comment.suggestion
                    ?.takeIf { settings.suggestions }
                    ?.let { SuggestionCheck.safe(it, file, startLine ?: comment.line, comment.line) },
            confidence = comment.confidence,
            fingerprint = Fingerprint.of(comment.path, comment.category, title, lineContent),
        )
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.6
        const val MAX_TITLE = 90
        const val MAX_BODY = 800
    }
}
