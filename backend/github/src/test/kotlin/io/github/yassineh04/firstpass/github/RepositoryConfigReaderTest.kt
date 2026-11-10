package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.settings.ConfigProblem
import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RepositoryConfigReaderTest {
    @Test
    fun `reads the YAML settings into the values core checks`() {
        val config =
            RepositoryConfigReader.read(
                """
                version: 1
                language: fr
                max_comments: 5
                focus: [bugs, security]
                exclude:
                  - "docs/**"
                instructions: |
                  Flag unjustified !! operators.
                """.trimIndent() + "\n",
            )

        config.issues.shouldBeEmpty()
        config.overrides shouldBe
            SettingsOverrides(
                language = ReviewLanguage.FR,
                maxComments = 5,
                focus = setOf(Category.BUG, Category.SECURITY),
                exclude = listOf("docs/**"),
                instructions = "Flag unjustified !! operators.\n",
            )
    }

    @Test
    fun `reads an empty file as no settings`() {
        RepositoryConfigReader.read("") shouldBe ParsedConfig(SettingsOverrides(), emptyList())
        RepositoryConfigReader.read("# only a comment\n") shouldBe ParsedConfig(SettingsOverrides(), emptyList())
    }

    @Test
    fun `reports a file that is not valid YAML, with the parser's explanation`() {
        val issue = RepositoryConfigReader.read("language: fr\n  max_comments: [5").issues.single()

        issue.problem shouldBe ConfigProblem.INVALID_FILE
        issue.detail.shouldNotBeNull()
    }

    @Test
    fun `follows YAML 1_2, where yes is text and not a boolean`() {
        val config = RepositoryConfigReader.read("skip_drafts: yes\nsuggestions: false\nmax_comments: 2.5")

        config.overrides shouldBe SettingsOverrides(suggestions = false)
        config.issues.map { it.setting } shouldContainExactly listOf("skip_drafts", "max_comments")
    }
}
