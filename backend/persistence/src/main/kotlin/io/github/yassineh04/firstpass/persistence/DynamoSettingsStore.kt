package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.settings.ProviderPreference
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.store.DashboardSettingsStore
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/**
 * The settings an admin saves in the dashboard (`REPO#{repoId}`, `SETTINGS`), design 8, access
 * pattern 3. A setting left unset is not stored, so that it keeps coming from the defaults.
 * Choices are stored by their name in FirstPass.
 */
class DynamoSettingsStore(
    private val table: FirstPassTable,
) : DashboardSettingsStore {
    override fun settings(repoId: Long): SettingsOverrides? =
        table.get(key(repoId))?.let {
            SettingsOverrides(
                enabled = it["enabled"]?.bool(),
                include = it.stringList("include"),
                exclude = it.stringList("exclude"),
                language = it.stringOrNull("language")?.let(ReviewLanguage::valueOf),
                severityThreshold = it.stringOrNull("severityThreshold")?.let(Severity::valueOf),
                maxComments = it["maxComments"]?.n()?.toInt(),
                focus = it.stringList("focus")?.map(Category::valueOf)?.toSet(),
                commentOn = it.stringOrNull("commentOn")?.let(CommentOn::valueOf),
                suggestions = it["suggestions"]?.bool(),
                instructions = it.stringOrNull("instructions"),
                maxChangedLines = it["maxChangedLines"]?.n()?.toInt(),
                skipDrafts = it["skipDrafts"]?.bool(),
                skipBotAuthors = it["skipBotAuthors"]?.bool(),
                provider = it.stringOrNull("provider")?.let(ProviderPreference::valueOf),
            )
        }

    /** Replaces the dashboard settings of [repoId]; the dashboard (phase 4) is its only writer. */
    fun save(
        repoId: Long,
        settings: SettingsOverrides,
    ) {
        val values: Map<String, AttributeValue?> =
            mapOf(
                "enabled" to settings.enabled?.let(Values::bool),
                "include" to settings.include?.let(Values::list),
                "exclude" to settings.exclude?.let(Values::list),
                "language" to settings.language?.let { Values.s(it.name) },
                "severityThreshold" to settings.severityThreshold?.let { Values.s(it.name) },
                "maxComments" to settings.maxComments?.let(Values::n),
                "focus" to settings.focus?.let { focus -> Values.list(focus.map { it.name }) },
                "commentOn" to settings.commentOn?.let { Values.s(it.name) },
                "suggestions" to settings.suggestions?.let(Values::bool),
                "instructions" to settings.instructions?.let(Values::s),
                "maxChangedLines" to settings.maxChangedLines?.let(Values::n),
                "skipDrafts" to settings.skipDrafts?.let(Values::bool),
                "skipBotAuthors" to settings.skipBotAuthors?.let(Values::bool),
                "provider" to settings.provider?.let { Values.s(it.name) },
            )
        table.put(key(repoId) + values.mapNotNull { (name, value) -> value?.let { name to it } })
    }

    private fun key(repoId: Long) = Keys.of(Keys.repository(repoId), "SETTINGS")
}
