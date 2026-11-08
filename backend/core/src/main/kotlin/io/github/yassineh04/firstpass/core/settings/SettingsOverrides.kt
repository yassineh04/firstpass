package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity

/**
 * The settings one source sets (dashboard or `.firstpass.yml`); null means "not set here", so
 * the value comes from the previous source.
 */
data class SettingsOverrides(
    val enabled: Boolean? = null,
    val include: List<String>? = null,
    val exclude: List<String>? = null,
    val language: ReviewLanguage? = null,
    val severityThreshold: Severity? = null,
    val maxComments: Int? = null,
    val focus: Set<Category>? = null,
    val commentOn: CommentOn? = null,
    val suggestions: Boolean? = null,
    val instructions: String? = null,
    val maxChangedLines: Int? = null,
    val skipDrafts: Boolean? = null,
    val skipBotAuthors: Boolean? = null,
    val provider: ProviderPreference? = null,
)

/** Merges the sources in order: built-in defaults, then the dashboard, then the repository file. */
object SettingsResolver {
    private val DEFAULTS = ReviewSettings()

    fun resolve(
        dashboard: SettingsOverrides?,
        file: SettingsOverrides?,
    ): ReviewSettings {
        val sources = listOfNotNull(file, dashboard)

        fun <T> pick(
            setting: (SettingsOverrides) -> T?,
            default: T,
        ): T = sources.firstNotNullOfOrNull(setting) ?: default

        return ReviewSettings(
            enabled = pick(SettingsOverrides::enabled, DEFAULTS.enabled),
            include = pick(SettingsOverrides::include, DEFAULTS.include),
            exclude = pick(SettingsOverrides::exclude, DEFAULTS.exclude),
            language = pick(SettingsOverrides::language, DEFAULTS.language),
            severityThreshold = pick(SettingsOverrides::severityThreshold, DEFAULTS.severityThreshold),
            maxComments = pick(SettingsOverrides::maxComments, DEFAULTS.maxComments),
            focus = pick(SettingsOverrides::focus, DEFAULTS.focus),
            commentOn = pick(SettingsOverrides::commentOn, DEFAULTS.commentOn),
            suggestions = pick(SettingsOverrides::suggestions, DEFAULTS.suggestions),
            instructions = pick(SettingsOverrides::instructions, DEFAULTS.instructions),
            maxChangedLines = pick(SettingsOverrides::maxChangedLines, DEFAULTS.maxChangedLines),
            skipDrafts = pick(SettingsOverrides::skipDrafts, DEFAULTS.skipDrafts),
            skipBotAuthors = pick(SettingsOverrides::skipBotAuthors, DEFAULTS.skipBotAuthors),
            provider = pick(SettingsOverrides::provider, DEFAULTS.provider),
        )
    }
}
