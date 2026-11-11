package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.settings.ProviderPreference
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DynamoSettingsStoreTest {
    private val store = DynamoSettingsStore(DynamoLocal.freshTable())

    @Test
    fun `has no settings for a repository the dashboard never configured`() {
        store.settings(7).shouldBeNull()
    }

    @Test
    fun `saves and reads back every dashboard setting`() {
        val settings =
            SettingsOverrides(
                enabled = false,
                include = listOf("src/**", "lib/**"),
                exclude = emptyList(),
                language = ReviewLanguage.FR,
                severityThreshold = Severity.MAJOR,
                maxComments = 4,
                focus = setOf(Category.SECURITY, Category.BUG),
                commentOn = CommentOn.entries.last(),
                suggestions = false,
                instructions = "Prefer early returns.",
                maxChangedLines = 800,
                skipDrafts = false,
                skipBotAuthors = true,
                provider = ProviderPreference.GROQ,
            )

        store.save(7, settings)

        store.settings(7) shouldBe settings
    }

    @Test
    fun `leaves unset what the admin did not set, and replaces the settings on each save`() {
        store.save(8, SettingsOverrides(maxComments = 2, language = ReviewLanguage.EN))
        store.save(8, SettingsOverrides(maxComments = 3))

        store.settings(8) shouldBe SettingsOverrides(maxComments = 3)
    }
}
