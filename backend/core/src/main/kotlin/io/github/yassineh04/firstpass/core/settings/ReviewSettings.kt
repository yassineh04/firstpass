package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity

/** Language of the review comments. Code identifiers are never translated. */
enum class ReviewLanguage(
    val englishName: String,
) {
    EN("English"),
    FR("French"),
}

/**
 * Effective review settings of a repository: built-in defaults, then dashboard settings,
 * then the `.firstpass.yml` file of the base branch. Fields are added as features need them.
 *
 * [include] and [exclude] are glob patterns on repository paths; an empty [include] means every
 * file is a candidate. [instructions] are the maintainers' custom instructions for the model.
 */
data class ReviewSettings(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val language: ReviewLanguage = ReviewLanguage.EN,
    val severityThreshold: Severity = Severity.MINOR,
    val maxComments: Int = 10,
    val focus: Set<Category> = DEFAULT_FOCUS,
    val commentOn: CommentOn = CommentOn.ADDED_LINES,
    val instructions: String = "",
) {
    companion object {
        val DEFAULT_FOCUS: Set<Category> =
            setOf(Category.BUG, Category.SECURITY, Category.PERFORMANCE, Category.RELIABILITY, Category.TESTS)
    }
}
