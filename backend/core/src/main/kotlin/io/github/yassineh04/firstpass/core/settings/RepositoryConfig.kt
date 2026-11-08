package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.prompt.PromptBuilder

/** What went wrong in `.firstpass.yml`; the review summary explains it in the review language. */
enum class ConfigProblem {
    /** Not YAML, or not a mapping of settings: the whole file is ignored. */
    INVALID_FILE,

    /** A `version` other than 1: the whole file is ignored. */
    UNSUPPORTED_VERSION,

    /** A key FirstPass does not know, often a typo: ignored. */
    UNKNOWN_SETTING,

    /** A value of the wrong type or outside the accepted values: this setting is ignored. */
    INVALID_VALUE,

    /** Instructions longer than the model receives: they are cut. */
    TOO_LONG,
}

/** What a setting accepts, so that the summary can explain an invalid value without repeating it. */
sealed interface Expectation {
    data class OneOf(
        val values: List<String>,
    ) : Expectation

    /** A non-empty list of values taken from [values]. */
    data class ListOf(
        val values: List<String>,
    ) : Expectation

    data class WholeNumber(
        val range: IntRange,
    ) : Expectation

    data object TrueOrFalse : Expectation

    data object Text : Expectation

    /** A list of valid glob patterns, or a single one. */
    data object Patterns : Expectation
}

/** One problem in the file. The invalid value itself is never kept: it is repository content. */
data class ConfigIssue(
    val setting: String?,
    val problem: ConfigProblem,
    val expected: Expectation? = null,
    val detail: String? = null,
)

data class ParsedConfig(
    val overrides: SettingsOverrides,
    val issues: List<ConfigIssue>,
)

/**
 * Reads the settings of `.firstpass.yml` from the generic tree the YAML adapter produces: maps
 * with string keys, lists, strings, booleans, numbers and nulls. A wrong setting is reported and
 * ignored, and the others still apply; a file that is not a mapping, or of an unknown version, is
 * ignored as a whole.
 */
object RepositoryConfig {
    const val FILE_NAME = ".firstpass.yml"
    private const val VERSION = "version"
    private const val INSTRUCTIONS = "instructions"
    private const val SUPPORTED_VERSION = 1L

    fun parse(tree: Any?): ParsedConfig =
        when {
            tree == null -> {
                ParsedConfig(SettingsOverrides(), emptyList())
            }

            tree !is Map<*, *> -> {
                ignored(ConfigIssue(null, ConfigProblem.INVALID_FILE))
            }

            !supported(tree[VERSION]) -> {
                ignored(
                    ConfigIssue(
                        VERSION,
                        ConfigProblem.UNSUPPORTED_VERSION,
                        Expectation.OneOf(listOf("$SUPPORTED_VERSION")),
                    ),
                )
            }

            else -> {
                readSettings(tree)
            }
        }

    /** The result for a file the YAML adapter could not read; [detail] is the parser's message. */
    fun unreadable(detail: String): ParsedConfig =
        ignored(ConfigIssue(null, ConfigProblem.INVALID_FILE, detail = detail))

    private fun readSettings(tree: Map<*, *>): ParsedConfig {
        var overrides = SettingsOverrides()
        val issues = mutableListOf<ConfigIssue>()
        for ((rawKey, value) in tree) {
            val key = rawKey.toString()
            if (key == VERSION || value == null) continue
            val setting = ConfigSettings.ALL[key]
            val applied = setting?.apply?.invoke(overrides, value)
            when {
                setting == null -> issues += ConfigIssue(key, ConfigProblem.UNKNOWN_SETTING)
                applied == null -> issues += ConfigIssue(key, ConfigProblem.INVALID_VALUE, setting.expected)
                else -> overrides = applied
            }
        }
        if ((overrides.instructions?.length ?: 0) > PromptBuilder.MAX_INSTRUCTIONS) {
            issues += ConfigIssue(INSTRUCTIONS, ConfigProblem.TOO_LONG)
        }
        return ParsedConfig(overrides, issues)
    }

    /** A missing version means version 1. */
    private fun supported(version: Any?): Boolean =
        version == null || ConfigSettings.wholeNumber(version) == SUPPORTED_VERSION || version == "$SUPPORTED_VERSION"

    private fun ignored(issue: ConfigIssue) = ParsedConfig(SettingsOverrides(), listOf(issue))
}
