package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.filter.Glob
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import java.math.BigInteger

/** One key of `.firstpass.yml`: what it accepts, and how a valid value sets it (null when invalid). */
internal class Setting(
    val expected: Expectation,
    val apply: (SettingsOverrides, Any) -> SettingsOverrides?,
)

/**
 * The keys of `.firstpass.yml` and the values they accept. Names and choices are case-insensitive;
 * `bugs` is accepted for `bug`, and a single pattern for a list of one.
 */
internal object ConfigSettings {
    private val CATEGORIES: Map<String, Category> =
        Category.entries.associateBy { it.wireName } + ("bugs" to Category.BUG)

    val ALL: Map<String, Setting> =
        mapOf(
            "enabled" to boolean { settings, value -> settings.copy(enabled = value) },
            "language" to
                choice(ReviewLanguage.entries.associateBy { it.name.lowercase() }) { s, v -> s.copy(language = v) },
            "severity_threshold" to
                choice(Severity.entries.associateBy { it.wireName }) { s, v -> s.copy(severityThreshold = v) },
            "max_comments" to number(ReviewSettings.MAX_COMMENTS_RANGE) { s, v -> s.copy(maxComments = v) },
            "comment_on" to
                choice(CommentOn.entries.associateBy { it.name.lowercase() }) { s, v -> s.copy(commentOn = v) },
            "focus" to
                Setting(Expectation.ListOf(Category.entries.map { it.wireName })) { s, v ->
                    categories(v)?.let { s.copy(focus = it) }
                },
            "include" to patterns { s, v -> s.copy(include = v) },
            "exclude" to patterns { s, v -> s.copy(exclude = v) },
            "max_changed_lines" to
                number(ReviewSettings.MAX_CHANGED_LINES_RANGE) { s, v -> s.copy(maxChangedLines = v) },
            "suggestions" to boolean { s, v -> s.copy(suggestions = v) },
            "skip_drafts" to boolean { s, v -> s.copy(skipDrafts = v) },
            "skip_bot_authors" to boolean { s, v -> s.copy(skipBotAuthors = v) },
            "provider" to
                choice(ProviderPreference.entries.associateBy { it.name.lowercase() }) { s, v -> s.copy(provider = v) },
            "instructions" to Setting(Expectation.Text) { s, v -> (v as? String)?.let { s.copy(instructions = it) } },
        )

    /** A YAML integer as a Long; decimals, booleans and text are not whole numbers. */
    fun wholeNumber(value: Any): Long? =
        when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is BigInteger -> value.toString().toLongOrNull()
            else -> null
        }

    private fun boolean(apply: (SettingsOverrides, Boolean) -> SettingsOverrides) =
        Setting(Expectation.TrueOrFalse) { settings, value -> (value as? Boolean)?.let { apply(settings, it) } }

    private fun number(
        range: IntRange,
        apply: (SettingsOverrides, Int) -> SettingsOverrides,
    ) = Setting(Expectation.WholeNumber(range)) { settings, value ->
        wholeNumber(
            value,
        )?.takeIf { it in range.first.toLong()..range.last.toLong() }?.let { apply(settings, it.toInt()) }
    }

    private fun <E> choice(
        choices: Map<String, E>,
        apply: (SettingsOverrides, E) -> SettingsOverrides,
    ) = Setting(Expectation.OneOf(choices.keys.toList())) { settings, value ->
        (value as? String)?.let { choices[it.trim().lowercase()] }?.let { apply(settings, it) }
    }

    private fun patterns(apply: (SettingsOverrides, List<String>) -> SettingsOverrides) =
        Setting(Expectation.Patterns) { settings, value -> globs(value)?.let { apply(settings, it) } }

    /** Every item must be a non-blank pattern that compiles; one bad item rejects the setting. */
    private fun globs(value: Any): List<String>? {
        val items = value as? List<*> ?: listOf(value)
        val patterns = items.filterIsInstance<String>().map(String::trim)
        val valid =
            patterns.size == items.size && patterns.all { it.isNotEmpty() && runCatching { Glob(it) }.isSuccess }
        return patterns.takeIf { valid }
    }

    /** A non-empty list of known categories, or a single one. */
    private fun categories(value: Any): Set<Category>? {
        val items = value as? List<*> ?: listOf(value)
        val categories = items.mapNotNull { item -> (item as? String)?.trim()?.lowercase()?.let(CATEGORIES::get) }
        return categories.toSet().takeIf { it.isNotEmpty() && categories.size == items.size }
    }
}
