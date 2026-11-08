package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RepositoryConfigTest {
    @Test
    fun `reads every setting of the documented example`() {
        val result =
            RepositoryConfig.parse(
                mapOf(
                    "version" to 1,
                    "enabled" to true,
                    "language" to "fr",
                    "severity_threshold" to "minor",
                    "max_comments" to 10,
                    "comment_on" to "added_lines",
                    "focus" to listOf("bugs", "security", "performance", "reliability", "tests"),
                    "include" to listOf("src/**", "backend/**", "frontend/src/**"),
                    "exclude" to listOf("**/*.lock", "**/generated/**", "docs/**"),
                    "max_changed_lines" to 1500,
                    "suggestions" to true,
                    "skip_drafts" to true,
                    "skip_bot_authors" to false,
                    "provider" to "groq",
                    "instructions" to "Kotlin: flag unjustified !! operators.\n",
                ),
            )

        result.issues.shouldBeEmpty()
        result.overrides shouldBe
            SettingsOverrides(
                enabled = true,
                include = listOf("src/**", "backend/**", "frontend/src/**"),
                exclude = listOf("**/*.lock", "**/generated/**", "docs/**"),
                language = ReviewLanguage.FR,
                severityThreshold = Severity.MINOR,
                maxComments = 10,
                focus =
                    setOf(
                        Category.BUG,
                        Category.SECURITY,
                        Category.PERFORMANCE,
                        Category.RELIABILITY,
                        Category.TESTS,
                    ),
                commentOn = CommentOn.ADDED_LINES,
                suggestions = true,
                instructions = "Kotlin: flag unjustified !! operators.\n",
                maxChangedLines = 1500,
                skipDrafts = true,
                skipBotAuthors = false,
                provider = ProviderPreference.GROQ,
            )
    }

    @Test
    fun `an empty file, or an empty value, sets nothing`() {
        for (tree in listOf(null, emptyMap<String, Any?>(), mapOf("language" to null))) {
            RepositoryConfig.parse(tree) shouldBe ParsedConfig(SettingsOverrides(), emptyList())
        }
    }

    @Test
    fun `reports each wrong value, ignores it, and still applies the other settings`() {
        val result =
            RepositoryConfig.parse(
                mapOf(
                    "language" to "de",
                    "max_comments" to -1,
                    "severity_threshold" to "major",
                    "focus" to listOf("style"),
                    "enabled" to "flase",
                    "comment_on" to "everywhere",
                ),
            )

        result.overrides shouldBe SettingsOverrides(severityThreshold = Severity.MAJOR)
        result.issues shouldContainExactly
            listOf(
                ConfigIssue("language", ConfigProblem.INVALID_VALUE, Expectation.OneOf(listOf("en", "fr"))),
                ConfigIssue("max_comments", ConfigProblem.INVALID_VALUE, Expectation.WholeNumber(0..50)),
                ConfigIssue(
                    "focus",
                    ConfigProblem.INVALID_VALUE,
                    Expectation.ListOf(Category.entries.map { it.wireName }),
                ),
                ConfigIssue("enabled", ConfigProblem.INVALID_VALUE, Expectation.TrueOrFalse),
                ConfigIssue(
                    "comment_on",
                    ConfigProblem.INVALID_VALUE,
                    Expectation.OneOf(listOf("added_lines", "diff_context")),
                ),
            )
    }

    @Test
    fun `reports an unknown setting, often a typo, and applies the rest`() {
        val result = RepositoryConfig.parse(mapOf("severity_treshold" to "nit", "language" to "fr"))

        result.overrides shouldBe SettingsOverrides(language = ReviewLanguage.FR)
        result.issues shouldContainExactly listOf(ConfigIssue("severity_treshold", ConfigProblem.UNKNOWN_SETTING))
    }

    @Test
    fun `ignores the whole file when its version is unknown or it is not a mapping`() {
        RepositoryConfig.parse(mapOf("version" to 2, "language" to "fr")) shouldBe
            ParsedConfig(
                SettingsOverrides(),
                listOf(ConfigIssue("version", ConfigProblem.UNSUPPORTED_VERSION, Expectation.OneOf(listOf("1")))),
            )
        for (tree in listOf(listOf("language"), "language: fr", 42)) {
            RepositoryConfig.parse(tree) shouldBe
                ParsedConfig(SettingsOverrides(), listOf(ConfigIssue(null, ConfigProblem.INVALID_FILE)))
        }
        RepositoryConfig.unreadable("mapping values are not allowed here, line 3") shouldBe
            ParsedConfig(
                SettingsOverrides(),
                listOf(
                    ConfigIssue(
                        null,
                        ConfigProblem.INVALID_FILE,
                        detail = "mapping values are not allowed here, line 3",
                    ),
                ),
            )
    }

    @Test
    fun `accepts values in any case, bug or bugs, and a single pattern instead of a list`() {
        RepositoryConfig.parse(
            mapOf(
                "language" to "FR",
                "severity_threshold" to "Critical",
                "focus" to listOf("bug", "Bugs", "SECURITY"),
                "exclude" to "docs/**",
            ),
        ) shouldBe
            ParsedConfig(
                SettingsOverrides(
                    language = ReviewLanguage.FR,
                    severityThreshold = Severity.CRITICAL,
                    focus = setOf(Category.BUG, Category.SECURITY),
                    exclude = listOf("docs/**"),
                ),
                emptyList(),
            )
    }

    @Test
    fun `rejects decimals, numbers instead of booleans, an empty focus and broken or blank patterns`() {
        val result =
            RepositoryConfig.parse(
                mapOf(
                    "max_changed_lines" to 2.5,
                    "suggestions" to 1,
                    "focus" to emptyList<String>(),
                    "include" to listOf("src/{main,test"),
                    "exclude" to listOf("docs/**", 42),
                    "instructions" to listOf("not", "text"),
                ),
            )

        result.overrides shouldBe SettingsOverrides()
        result.issues.map { it.setting to it.expected } shouldContainExactly
            listOf(
                "max_changed_lines" to Expectation.WholeNumber(1..20_000),
                "suggestions" to Expectation.TrueOrFalse,
                "focus" to Expectation.ListOf(Category.entries.map { it.wireName }),
                "include" to Expectation.Patterns,
                "exclude" to Expectation.Patterns,
                "instructions" to Expectation.Text,
            )
        RepositoryConfig
            .parse(
                mapOf("exclude" to listOf("docs/**", " ")),
            ).issues
            .map { it.setting } shouldContainExactly
            listOf("exclude")
    }

    @Test
    fun `keeps long instructions, and warns that the model only reads the first 1000 characters`() {
        val long = "x".repeat(1001)

        RepositoryConfig.parse(mapOf("instructions" to long)) shouldBe
            ParsedConfig(
                SettingsOverrides(instructions = long),
                listOf(ConfigIssue("instructions", ConfigProblem.TOO_LONG)),
            )
        RepositoryConfig.parse(mapOf("instructions" to "x".repeat(1000))).issues.shouldBeEmpty()
    }
}
