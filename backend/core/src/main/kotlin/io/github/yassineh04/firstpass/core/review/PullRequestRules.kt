package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.settings.ReviewSettings

/** What the rules need to know about a pull request, read from the webhook or the API. */
data class PullRequestFacts(
    val draft: Boolean,
    val authorLogin: String,
    /** GitHub's account type of the author: `User`, `Bot` or `Organization`. */
    val authorType: String,
    val additions: Int,
    val deletions: Int,
)

/** Why a pull request is left alone without a word. */
enum class SilentSkip {
    DISABLED,
    DRAFT,
    BOT_AUTHOR,
}

sealed interface RuleDecision {
    data object Review : RuleDecision

    /** No comment, no check run, no quota used. */
    data class Skip(
        val reason: SilentSkip,
    ) : RuleDecision

    /** One explanatory comment, then nothing else: no model call. */
    data class TooLarge(
        val changedLines: Int,
        val limit: Int,
    ) : RuleDecision
}

/** The rules that depend on the settings (design 7.1, step 9), checked before any quota is used. */
object PullRequestRules {
    fun check(
        pullRequest: PullRequestFacts,
        settings: ReviewSettings,
    ): RuleDecision {
        val changedLines = pullRequest.additions + pullRequest.deletions
        return when {
            !settings.enabled -> RuleDecision.Skip(SilentSkip.DISABLED)
            pullRequest.draft && settings.skipDrafts -> RuleDecision.Skip(SilentSkip.DRAFT)
            isBot(pullRequest) && settings.skipBotAuthors -> RuleDecision.Skip(SilentSkip.BOT_AUTHOR)
            changedLines > settings.maxChangedLines -> RuleDecision.TooLarge(changedLines, settings.maxChangedLines)
            else -> RuleDecision.Review
        }
    }

    /** GitHub gives app accounts (Dependabot, Renovate) the type `Bot` and a login ending in `[bot]`. */
    private fun isBot(pullRequest: PullRequestFacts): Boolean =
        pullRequest.authorType.equals("Bot", ignoreCase = true) || pullRequest.authorLogin.endsWith("[bot]")
}
