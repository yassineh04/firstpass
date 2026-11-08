package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PullRequestRulesTest {
    private val ordinary =
        PullRequestFacts(draft = false, authorLogin = "octocat", authorType = "User", additions = 10, deletions = 5)

    @Test
    fun `reviews an ordinary pull request`() {
        PullRequestRules.check(ordinary, ReviewSettings()) shouldBe RuleDecision.Review
    }

    @Test
    fun `skips silently when reviews are off, for drafts and for bots, unless the settings say otherwise`() {
        val draft = ordinary.copy(draft = true)
        val bot = ordinary.copy(authorLogin = "renovate[bot]", authorType = "Bot")

        PullRequestRules.check(ordinary, ReviewSettings(enabled = false)) shouldBe
            RuleDecision.Skip(SilentSkip.DISABLED)
        PullRequestRules.check(draft, ReviewSettings()) shouldBe RuleDecision.Skip(SilentSkip.DRAFT)
        PullRequestRules.check(draft, ReviewSettings(skipDrafts = false)) shouldBe RuleDecision.Review
        PullRequestRules.check(bot, ReviewSettings()) shouldBe RuleDecision.Skip(SilentSkip.BOT_AUTHOR)
        PullRequestRules.check(bot.copy(authorType = "bot"), ReviewSettings()) shouldBe
            RuleDecision.Skip(SilentSkip.BOT_AUTHOR)
        PullRequestRules.check(bot.copy(authorType = "User"), ReviewSettings()) shouldBe
            RuleDecision.Skip(SilentSkip.BOT_AUTHOR)
        PullRequestRules.check(bot, ReviewSettings(skipBotAuthors = false)) shouldBe RuleDecision.Review
    }

    @Test
    fun `refuses a pull request with more changed lines than the limit, which is allowed itself`() {
        PullRequestRules.check(ordinary.copy(additions = 1000, deletions = 501), ReviewSettings()) shouldBe
            RuleDecision.TooLarge(changedLines = 1501, limit = 1500)
        PullRequestRules.check(ordinary.copy(additions = 1000, deletions = 500), ReviewSettings()) shouldBe
            RuleDecision.Review
        PullRequestRules.check(ordinary, ReviewSettings(maxChangedLines = 14)) shouldBe
            RuleDecision.TooLarge(changedLines = 15, limit = 14)
    }

    @Test
    fun `checks in order off, draft, bot, size, so that a large draft gets no comment before it is ready`() {
        val everything =
            PullRequestFacts(
                draft = true,
                authorLogin = "dependabot[bot]",
                authorType = "Bot",
                additions = 9000,
                deletions = 0,
            )

        PullRequestRules.check(everything, ReviewSettings(enabled = false)) shouldBe
            RuleDecision.Skip(SilentSkip.DISABLED)
        PullRequestRules.check(everything, ReviewSettings()) shouldBe RuleDecision.Skip(SilentSkip.DRAFT)
        PullRequestRules.check(everything.copy(draft = false), ReviewSettings()) shouldBe
            RuleDecision.Skip(SilentSkip.BOT_AUTHOR)
    }
}
