package io.github.yassineh04.firstpass.core.quota

import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class QuotaPolicyTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC)
    private val policy = QuotaPolicy(QuotaLimits(reviewsPerInstallationPerDay = 20, llmCallsPerDay = 300), clock)

    @Test
    fun `allows a review while both daily counters stay under their limits`() {
        policy.checkReview(DailyUsage(installationReviews = 19, globalLlmCalls = 294), plannedLlmCalls = 6) shouldBe
            QuotaDecision.Allowed
    }

    @Test
    fun `refuses a review when the installation used all its reviews of the day`() {
        policy.checkReview(DailyUsage(installationReviews = 20, globalLlmCalls = 0), plannedLlmCalls = 1) shouldBe
            QuotaDecision.Exceeded(QuotaReason.INSTALLATION_REVIEWS)
    }

    @Test
    fun `refuses a review whose planned calls would pass the global limit`() {
        policy.checkReview(DailyUsage(installationReviews = 0, globalLlmCalls = 295), plannedLlmCalls = 6) shouldBe
            QuotaDecision.Exceeded(QuotaReason.GLOBAL_LLM_CALLS)
    }

    @Test
    fun `reports the installation limit first when both are reached`() {
        policy.checkReview(DailyUsage(installationReviews = 20, globalLlmCalls = 300), plannedLlmCalls = 1) shouldBe
            QuotaDecision.Exceeded(QuotaReason.INSTALLATION_REVIEWS)
    }

    @Test
    fun `counts days in UTC, whatever the time zone of the clock`() {
        val montrealEvening = Clock.fixed(Instant.parse("2026-11-08T02:00:00Z"), ZoneId.of("America/Montreal"))

        QuotaPolicy(QuotaLimits(), montrealEvening).today() shouldBe LocalDate.parse("2026-11-08")
        QuotaPolicy(QuotaLimits(), Clock.fixed(Instant.parse("2026-11-07T23:59:59Z"), ZoneOffset.UTC)).today() shouldBe
            LocalDate.parse("2026-11-07")
        policy.dayKey() shouldBe "2026-11-07"
    }

    @Test
    fun `posts the quota notice at most once per pull request and per day`() {
        policy.shouldPostNotice(lastNoticeDay = null) shouldBe true
        policy.shouldPostNotice(lastNoticeDay = LocalDate.parse("2026-11-06")) shouldBe true
        policy.shouldPostNotice(lastNoticeDay = LocalDate.parse("2026-11-07")) shouldBe false
    }

    @Test
    fun `picks the first provider whose daily token allowance still fits the batch`() {
        val providers =
            listOf(
                ProviderAllowance(name = "gemini", tokensUsedToday = 995_000, tokensPerDay = 1_000_000),
                ProviderAllowance(name = "groq", tokensUsedToday = 150_000, tokensPerDay = 200_000),
            )

        policy.providerFor(providers, estimatedTokens = 6_500) shouldBe "groq"
        policy.providerFor(providers, estimatedTokens = 60_000).shouldBeNull()
    }

    @Test
    fun `rejects limits that are not positive`() {
        shouldThrow<IllegalArgumentException> { QuotaLimits(reviewsPerInstallationPerDay = 0) }
        shouldThrow<IllegalArgumentException> { QuotaLimits(llmCallsPerDay = -1) }
    }

    @Test
    fun `explains the reached quota in the review language`() {
        QuotaNotice.comment(QuotaReason.INSTALLATION_REVIEWS, ReviewLanguage.EN) shouldContain "midnight UTC"
        QuotaNotice.comment(QuotaReason.GLOBAL_LLM_CALLS, ReviewLanguage.FR) shouldContain "minuit UTC"
        QuotaNotice.checkRunTitle(ReviewLanguage.EN) shouldBe "Daily quota reached"
        QuotaNotice.checkRunTitle(ReviewLanguage.FR) shouldBe "Quota du jour atteint"
    }
}
