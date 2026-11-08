package io.github.yassineh04.firstpass.core.settings

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SettingsResolverTest {
    @Test
    fun `uses the built-in defaults when no source sets anything`() {
        SettingsResolver.resolve(null, null) shouldBe ReviewSettings()
        SettingsResolver.resolve(SettingsOverrides(), SettingsOverrides()) shouldBe ReviewSettings()
    }

    @Test
    fun `lets the dashboard override the defaults, and the repository file override the dashboard`() {
        val dashboard = SettingsOverrides(language = ReviewLanguage.FR, maxComments = 5, suggestions = false)
        val file = SettingsOverrides(maxComments = 3, provider = ProviderPreference.GEMINI)

        SettingsResolver.resolve(dashboard, file) shouldBe
            ReviewSettings(
                language = ReviewLanguage.FR,
                maxComments = 3,
                suggestions = false,
                provider = ProviderPreference.GEMINI,
            )
    }

    @Test
    fun `every setting comes from the file, else the dashboard, else the default`() {
        runBlocking {
            checkAll(ITERATIONS, overrides, overrides) { dashboard, file ->
                val settings = SettingsResolver.resolve(dashboard, file)
                val sources = Sources(dashboard, file)

                sources.check(settings.enabled, ReviewSettings::enabled, SettingsOverrides::enabled)
                sources.check(settings.include, ReviewSettings::include, SettingsOverrides::include)
                sources.check(settings.exclude, ReviewSettings::exclude, SettingsOverrides::exclude)
                sources.check(settings.language, ReviewSettings::language, SettingsOverrides::language)
                sources.check(
                    settings.severityThreshold,
                    ReviewSettings::severityThreshold,
                    SettingsOverrides::severityThreshold,
                )
                sources.check(settings.maxComments, ReviewSettings::maxComments, SettingsOverrides::maxComments)
                sources.check(settings.focus, ReviewSettings::focus, SettingsOverrides::focus)
                sources.check(settings.commentOn, ReviewSettings::commentOn, SettingsOverrides::commentOn)
                sources.check(settings.suggestions, ReviewSettings::suggestions, SettingsOverrides::suggestions)
                sources.check(settings.instructions, ReviewSettings::instructions, SettingsOverrides::instructions)
                sources.check(
                    settings.maxChangedLines,
                    ReviewSettings::maxChangedLines,
                    SettingsOverrides::maxChangedLines,
                )
                sources.check(settings.skipDrafts, ReviewSettings::skipDrafts, SettingsOverrides::skipDrafts)
                sources.check(
                    settings.skipBotAuthors,
                    ReviewSettings::skipBotAuthors,
                    SettingsOverrides::skipBotAuthors,
                )
                sources.check(settings.provider, ReviewSettings::provider, SettingsOverrides::provider)
            }
        }
    }

    private class Sources(
        val dashboard: SettingsOverrides,
        val file: SettingsOverrides,
    ) {
        /** The resolved value is the file's, else the dashboard's, else the default. */
        fun <T> check(
            actual: T,
            default: (ReviewSettings) -> T,
            source: (SettingsOverrides) -> T?,
        ) {
            actual shouldBe (source(file) ?: source(dashboard) ?: default(ReviewSettings()))
        }
    }

    private companion object {
        const val ITERATIONS = 300

        val patterns = Arb.list(Arb.string(1..10), 0..3).orNull()

        val overrides: Arb<SettingsOverrides> =
            arbitrary {
                SettingsOverrides(
                    enabled = Arb.boolean().orNull().bind(),
                    include = patterns.bind(),
                    exclude = patterns.bind(),
                    language = Arb.element(ReviewLanguage.entries).orNull().bind(),
                    severityThreshold = Arb.element(Severity.entries).orNull().bind(),
                    maxComments = Arb.int(ReviewSettings.MAX_COMMENTS_RANGE).orNull().bind(),
                    focus = Arb.set(Arb.element(Category.entries), 1..3).orNull().bind(),
                    commentOn = Arb.element(CommentOn.entries).orNull().bind(),
                    suggestions = Arb.boolean().orNull().bind(),
                    instructions = Arb.string(0..20).orNull().bind(),
                    maxChangedLines = Arb.int(ReviewSettings.MAX_CHANGED_LINES_RANGE).orNull().bind(),
                    skipDrafts = Arb.boolean().orNull().bind(),
                    skipBotAuthors = Arb.boolean().orNull().bind(),
                    provider = Arb.element(ProviderPreference.entries).orNull().bind(),
                )
            }
    }
}
