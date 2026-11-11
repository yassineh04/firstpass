package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.store.UsageStore
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The daily counters (design 7.9 and 8, access pattern 9): one item per installation, per
 * provider (`USAGE#PROVIDER#{name}`) and for everyone (`USAGE#GLOBAL`), under `DAY#{yyyy-mm-dd}`,
 * kept 35 days. Every change is a single atomic UpdateItem with ADD, so that two jobs running at
 * once can never both take the last review of the day.
 *
 * [providers] are the names of the configured providers, read together by [providerTokens].
 */
class DynamoUsageStore(
    private val table: FirstPassTable,
    private val providers: List<String>,
) : UsageStore {
    override fun reserveReview(
        installationId: Long,
        day: String,
        limit: Int,
    ): Boolean =
        try {
            table.client.updateItem {
                it
                    .tableName(table.name)
                    .key(Keys.of(installation(installationId), dayKey(day)))
                    .updateExpression("ADD #reviews :one SET #expiresAt = if_not_exists(#expiresAt, :expiresAt)")
                    .conditionExpression("attribute_not_exists(#reviews) OR #reviews < :limit")
                    .expressionAttributeNames(mapOf("#reviews" to REVIEWS, "#expiresAt" to Attr.EXPIRES_AT))
                    .expressionAttributeValues(
                        mapOf(":one" to Values.n(1), ":limit" to Values.n(limit), ":expiresAt" to expiry(day)),
                    )
            }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }

    override fun globalLlmCalls(day: String): Int = table.get(Keys.of(GLOBAL, dayKey(day)))?.counter(LLM_CALLS) ?: 0

    override fun providerTokens(day: String): Map<String, Int> =
        table
            .getAll(providers.map { Keys.of(provider(it), dayKey(day)) })
            .associate {
                it.string(Attr.PK).removePrefix(PROVIDER_PREFIX) to
                    it.counter(TOKENS_IN) + it.counter(TOKENS_OUT)
            }

    override fun addConsumption(
        installationId: Long,
        day: String,
        usage: List<ProviderUsage>,
    ) {
        if (usage.isEmpty()) return
        add(installation(installationId), day, usage)
        add(GLOBAL, day, usage)
        usage.groupBy { it.provider }.forEach { (name, used) -> add(provider(name), day, used) }
    }

    private fun add(
        pk: String,
        day: String,
        usage: List<ProviderUsage>,
    ) {
        table.client.updateItem {
            it
                .tableName(table.name)
                .key(Keys.of(pk, dayKey(day)))
                .updateExpression(
                    "ADD #calls :calls, #in :in, #out :out SET #expiresAt = if_not_exists(#expiresAt, :expiresAt)",
                ).expressionAttributeNames(
                    mapOf(
                        "#calls" to LLM_CALLS,
                        "#in" to TOKENS_IN,
                        "#out" to TOKENS_OUT,
                        "#expiresAt" to Attr.EXPIRES_AT,
                    ),
                ).expressionAttributeValues(
                    mapOf(
                        ":calls" to Values.n(usage.sumOf { used -> used.calls }),
                        ":in" to Values.n(usage.sumOf { used -> used.inputTokens }),
                        ":out" to Values.n(usage.sumOf { used -> used.outputTokens }),
                        ":expiresAt" to expiry(day),
                    ),
                )
        }
    }

    private fun installation(installationId: Long) = "USAGE#$installationId"

    private fun provider(name: String) = "$PROVIDER_PREFIX$name"

    private fun dayKey(day: String) = "DAY#$day"

    private fun expiry(day: String) =
        Values.n(
            LocalDate
                .parse(day)
                .plusDays(KEPT_DAYS)
                .atStartOfDay()
                .toEpochSecond(ZoneOffset.UTC),
        )

    private companion object {
        const val GLOBAL = "USAGE#GLOBAL"
        const val PROVIDER_PREFIX = "USAGE#PROVIDER#"
        const val REVIEWS = "reviews"
        const val LLM_CALLS = "llmCalls"
        const val TOKENS_IN = "tokensIn"
        const val TOKENS_OUT = "tokensOut"
        const val KEPT_DAYS = 35L
    }
}
