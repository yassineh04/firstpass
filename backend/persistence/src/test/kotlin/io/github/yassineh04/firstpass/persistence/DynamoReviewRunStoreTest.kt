package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.store.FindingRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunRecord
import io.github.yassineh04.firstpass.core.store.RunStatus
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DynamoReviewRunStoreTest {
    private val table = DynamoLocal.freshTable()
    private val store = DynamoReviewRunStore(table)

    private fun run(
        runId: String = "delivery-1",
        receivedAt: Instant = T0,
    ) = ReviewRunRecord(
        runId = runId,
        repoId = 7,
        pullRequest = 3,
        headSha = "head1",
        trigger = ReviewTrigger.SYNCHRONIZE,
        status = RunStatus.PUBLISHED,
        receivedAt = receivedAt,
        startedAt = receivedAt.plusMillis(1500),
        durationMs = 12_000,
        usage = listOf(ProviderUsage("gemini", "model-a", 2, 3000, 400), ProviderUsage("groq", null, 1, 500, 80)),
        filesReviewed = 4,
        filesSkipped = 1,
        findingsPosted = 2,
        findingsSummarized = 28,
    )

    private fun findings(
        runId: String,
        count: Int,
    ) = List(count) { index ->
        val finding =
            Finding(
                path = "src/A.kt",
                startLine = if (index == 0) 10 else null,
                line = 12,
                severity = Severity.MAJOR,
                category = Category.SECURITY,
                title = "Finding $index",
                body = "Explains finding $index.",
                suggestion = if (index == 0) "val x = 1" else null,
                confidence = 0.85,
                fingerprint = "f$index",
            )
        FindingRecord(runId, index, finding, inline = index < 2)
    }

    @Test
    fun `saves a run under its reception time, with its 30 findings in order`() {
        store.save(run(), findings("delivery-1", 30))

        val item = table.items("REPO#7", "RUN#").single()
        item.string("sk") shouldBe "RUN#2026-11-09T10:00:00.000Z#delivery-1"
        item.string("status") shouldBe "PUBLISHED"
        item.string("trigger") shouldBe "SYNCHRONIZE"
        item.string("startedAt") shouldBe "2026-11-09T10:00:01.500Z"
        item.int("findingsSummarized") shouldBe 28
        item.getValue("usage").l().map { it.m().getValue("provider").s() } shouldContainExactly listOf("gemini", "groq")
        item.long("expiresAt") shouldBe T0.plus(Duration.ofDays(180)).epochSecond

        val stored = table.items("RUN#delivery-1")
        stored.map { it.string("sk") } shouldContainExactly (0 until 30).map { "FINDING#" + "$it".padStart(3, '0') }
        stored.first().string("suggestion") shouldBe "val x = 1"
        stored.first().int("startLine") shouldBe 10
        stored.map { it.flag("inline") }.count { it } shouldBe 2
        stored.last().string("feedback") shouldBe "none"
    }

    @Test
    fun `finds the runs of a pull request through GSI1, newest first`() {
        // With Instant.toString, "10:00:00Z" would sort after "10:00:00.500Z".
        store.save(run("first", T0), emptyList())
        store.save(run("second", T0.plusMillis(500)), emptyList())

        val newestFirst =
            DynamoLocal.client
                .query {
                    it
                        .tableName(table.name)
                        .indexName("GSI1")
                        .keyConditionExpression("gsi1pk = :pr")
                        .expressionAttributeValues(mapOf(":pr" to Values.s("PR#7#3")))
                        .scanIndexForward(false)
                }.items()
        newestFirst.map { it.string("runId") } shouldContainExactly listOf("second", "first")
    }

    @Test
    fun `overwrites the run of a replayed job and deletes the findings it no longer has`() {
        store.save(run(), findings("delivery-1", 5))

        store.save(run().copy(status = RunStatus.NOTHING_NEW), findings("delivery-1", 2))

        table.items("REPO#7", "RUN#").single().string("status") shouldBe "NOTHING_NEW"
        table.items("RUN#delivery-1").map { it.string("sk") } shouldContainExactly listOf("FINDING#000", "FINDING#001")
    }
}
