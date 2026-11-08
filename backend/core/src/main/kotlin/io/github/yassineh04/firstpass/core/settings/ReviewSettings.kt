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

/** Which model provider a repository prefers; AUTO keeps the default order (Gemini, then Groq). */
enum class ProviderPreference {
    AUTO,
    GEMINI,
    GROQ,
}

/**
 * Effective review settings of a repository: built-in defaults, then dashboard settings,
 * then the `.firstpass.yml` file of the base branch (see [SettingsResolver]).
 *
 * [include] and [exclude] are glob patterns on repository paths; an empty [include] means every
 * file is a candidate. [instructions] are the maintainers' custom instructions for the model.
 * [enabled] only lets a repository turn reviews off: a private repository still needs the
 * consent recorded with the repository itself.
 */
data class ReviewSettings(
    val enabled: Boolean = true,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val language: ReviewLanguage = ReviewLanguage.EN,
    val severityThreshold: Severity = Severity.MINOR,
    val maxComments: Int = 10,
    val focus: Set<Category> = DEFAULT_FOCUS,
    val commentOn: CommentOn = CommentOn.ADDED_LINES,
    val suggestions: Boolean = true,
    val instructions: String = "",
    val maxChangedLines: Int = 1500,
    val skipDrafts: Boolean = true,
    val skipBotAuthors: Boolean = true,
    val provider: ProviderPreference = ProviderPreference.AUTO,
) {
    init {
        require(maxComments in MAX_COMMENTS_RANGE) { "maxComments must be in $MAX_COMMENTS_RANGE, got $maxComments" }
        require(maxChangedLines in MAX_CHANGED_LINES_RANGE) {
            "maxChangedLines must be in $MAX_CHANGED_LINES_RANGE, got $maxChangedLines"
        }
        require(focus.isNotEmpty()) { "focus needs at least one category" }
    }

    companion object {
        val DEFAULT_FOCUS: Set<Category> =
            setOf(Category.BUG, Category.SECURITY, Category.PERFORMANCE, Category.RELIABILITY, Category.TESTS)

        /** Zero keeps every finding in the review summary. */
        val MAX_COMMENTS_RANGE = 0..50
        val MAX_CHANGED_LINES_RANGE = 1..20_000
    }
}
