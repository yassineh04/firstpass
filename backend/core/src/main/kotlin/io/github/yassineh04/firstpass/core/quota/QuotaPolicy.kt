package io.github.yassineh04.firstpass.core.quota

import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Daily limits that keep FirstPass inside the free tiers. Both are configurable. */
data class QuotaLimits(
    val reviewsPerInstallationPerDay: Int = 20,
    val llmCallsPerDay: Int = 300,
) {
    init {
        require(reviewsPerInstallationPerDay > 0) { "The daily review limit must be positive" }
        require(llmCallsPerDay > 0) { "The daily LLM call limit must be positive" }
    }
}

/** Counters of the current UTC day, read from storage before a review starts. */
data class DailyUsage(
    val installationReviews: Int,
    val globalLlmCalls: Int,
)

enum class QuotaReason {
    INSTALLATION_REVIEWS,
    GLOBAL_LLM_CALLS,

    /** No provider has enough of its daily token allowance left for the review. */
    PROVIDER_TOKENS,
}

sealed interface QuotaDecision {
    data object Allowed : QuotaDecision

    data class Exceeded(
        val reason: QuotaReason,
    ) : QuotaDecision
}

/** What a model provider may still consume today. [name] is the provider id used by the llm adapter. */
data class ProviderAllowance(
    val name: String,
    val tokensUsedToday: Int,
    val tokensPerDay: Int,
)

/**
 * Daily quota decisions. Days are UTC days, whatever the time zone of [clock]. The counters
 * themselves are reserved atomically by the storage adapter; this class only decides.
 */
class QuotaPolicy(
    val limits: QuotaLimits,
    private val clock: Clock,
) {
    fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** The `yyyy-MM-dd` key of today's counters. */
    fun dayKey(): String = today().toString()

    /**
     * A review needs one installation review and one LLM call per batch. It is refused when it
     * could not finish within today's limits, so that no review stops halfway.
     */
    fun checkReview(
        usage: DailyUsage,
        plannedLlmCalls: Int,
    ): QuotaDecision =
        when {
            usage.installationReviews >= limits.reviewsPerInstallationPerDay -> {
                QuotaDecision.Exceeded(QuotaReason.INSTALLATION_REVIEWS)
            }

            usage.globalLlmCalls + plannedLlmCalls > limits.llmCallsPerDay -> {
                QuotaDecision.Exceeded(QuotaReason.GLOBAL_LLM_CALLS)
            }

            else -> {
                QuotaDecision.Allowed
            }
        }

    /** The quota notice is posted at most once per pull request and per UTC day. */
    fun shouldPostNotice(lastNoticeDay: LocalDate?): Boolean = lastNoticeDay != today()

    /** The first provider, in preference order, whose daily token allowance still fits the batch. */
    fun providerFor(
        providers: List<ProviderAllowance>,
        estimatedTokens: Int,
    ): String? = providers.firstOrNull { it.tokensUsedToday + estimatedTokens <= it.tokensPerDay }?.name
}

/** Texts published when a quota is reached: one comment per pull request and per day, and a neutral check run. */
object QuotaNotice {
    private class Texts(
        val reached: Map<QuotaReason, String>,
        val reset: String,
    )

    private val COMMENTS =
        mapOf(
            ReviewLanguage.EN to
                Texts(
                    reached =
                        mapOf(
                            QuotaReason.INSTALLATION_REVIEWS to
                                "FirstPass reached the daily review limit of this account, " +
                                "so it did not review this pull request.",
                            QuotaReason.GLOBAL_LLM_CALLS to
                                "FirstPass reached its daily limit of AI requests, " +
                                "so it did not review this pull request.",
                            QuotaReason.PROVIDER_TOKENS to
                                "FirstPass used up today's AI capacity, so it did not review this pull request.",
                        ),
                    reset = "The limit resets at midnight UTC; the next push after that will be reviewed.",
                ),
            ReviewLanguage.FR to
                Texts(
                    reached =
                        mapOf(
                            QuotaReason.INSTALLATION_REVIEWS to
                                "FirstPass a atteint la limite quotidienne de revues de ce compte " +
                                "et n'a donc pas relu cette pull request.",
                            QuotaReason.GLOBAL_LLM_CALLS to
                                "FirstPass a atteint sa limite quotidienne de requêtes d'IA " +
                                "et n'a donc pas relu cette pull request.",
                            QuotaReason.PROVIDER_TOKENS to
                                "FirstPass a épuisé sa capacité d'IA du jour et n'a donc pas relu cette pull request.",
                        ),
                    reset = "La limite repart à zéro à minuit UTC ; le prochain push après cette heure sera relu.",
                ),
        )

    private val CHECK_RUN_TITLES =
        mapOf(
            ReviewLanguage.EN to "Daily quota reached",
            ReviewLanguage.FR to "Quota du jour atteint",
        )

    fun comment(
        reason: QuotaReason,
        language: ReviewLanguage,
    ): String = COMMENTS.getValue(language).let { "${it.reached.getValue(reason)} ${it.reset}" }

    fun checkRunTitle(language: ReviewLanguage): String = CHECK_RUN_TITLES.getValue(language)
}
