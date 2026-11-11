package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.store.FindingRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunStore
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Duration

/**
 * Review runs (`REPO#{repoId}`, `RUN#{receivedAt}#{runId}`, also indexed by GSI1 under
 * `PR#{repoId}#{number}`) and their findings (`RUN#{runId}`, `FINDING#{index}`), kept 180 days
 * (design 8, access patterns 5 to 7). A replayed job has the same keys and overwrites its run.
 */
class DynamoReviewRunStore(
    private val table: FirstPassTable,
) : ReviewRunStore {
    /** Findings a previous attempt of the same job had beyond the new ones are deleted. */
    override fun save(
        run: ReviewRunRecord,
        findings: List<FindingRecord>,
    ) {
        val expiresAt = Values.expiry(run.receivedAt.plus(KEPT))
        val stale =
            table
                .query(Keys.run(run.runId), Keys.FINDING_PREFIX)
                .filter { it.string(Attr.SK) >= findingKey(findings.size) }
                .map { it.key() }
        table.writeAll(
            items = listOf(runItem(run, expiresAt)) + findings.map { findingItem(it, expiresAt) },
            deletions = stale,
        )
    }

    private fun runItem(
        run: ReviewRunRecord,
        expiresAt: AttributeValue,
    ): Item {
        val time = Keys.sortable(run.receivedAt)
        return Keys.of(Keys.repository(run.repoId), "${Keys.RUN_PREFIX}$time#${run.runId}") +
            mapOf(
                Attr.GSI1_PK to Values.s("${Keys.PR_PREFIX}${run.repoId}#${run.pullRequest}"),
                Attr.GSI1_SK to Values.s("${Keys.RUN_PREFIX}$time"),
                "runId" to Values.s(run.runId),
                "prNumber" to Values.n(run.pullRequest),
                "headSha" to Values.s(run.headSha),
                "trigger" to Values.s(run.trigger.name),
                "status" to Values.s(run.status.name),
                "receivedAt" to Values.time(run.receivedAt),
                "startedAt" to Values.time(run.startedAt),
                "durationMs" to Values.n(run.durationMs),
                "usage" to AttributeValue.fromL(run.usage.map(::usageOf)),
                "filesReviewed" to Values.n(run.filesReviewed),
                "filesSkipped" to Values.n(run.filesSkipped),
                "findingsPosted" to Values.n(run.findingsPosted),
                "findingsSummarized" to Values.n(run.findingsSummarized),
                Attr.EXPIRES_AT to expiresAt,
            )
    }

    private fun usageOf(usage: ProviderUsage): AttributeValue =
        AttributeValue.fromM(
            buildMap {
                put("provider", Values.s(usage.provider))
                usage.model?.let { put("model", Values.s(it)) }
                put("calls", Values.n(usage.calls))
                put("tokensIn", Values.n(usage.inputTokens))
                put("tokensOut", Values.n(usage.outputTokens))
            },
        )

    private fun findingItem(
        record: FindingRecord,
        expiresAt: AttributeValue,
    ): Item {
        val finding = record.finding
        return buildMap {
            putAll(Keys.of(Keys.run(record.runId), findingKey(record.index)))
            put("path", Values.s(finding.path))
            put("line", Values.n(finding.line))
            finding.startLine?.let { put("startLine", Values.n(it)) }
            put("severity", Values.s(finding.severity.name))
            put("category", Values.s(finding.category.name))
            put("title", Values.s(finding.title))
            put("body", Values.s(finding.body))
            finding.suggestion?.let { put("suggestion", Values.s(it)) }
            put("confidence", Values.n(finding.confidence))
            put("fingerprint", Values.s(finding.fingerprint))
            put("inline", Values.bool(record.inline))
            put("feedback", Values.s("none"))
            put(Attr.EXPIRES_AT, expiresAt)
        }
    }

    /** Three digits, so that the text order is the order of the findings. */
    private fun findingKey(index: Int) = Keys.FINDING_PREFIX + index.toString().padStart(INDEX_DIGITS, '0')

    private companion object {
        val KEPT: Duration = Duration.ofDays(180)
        const val INDEX_DIGITS = 3
    }
}
