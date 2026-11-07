package io.github.yassineh04.firstpass.core.finding

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class SelectionTest {
    private fun finding(
        id: String,
        severity: Severity = Severity.MAJOR,
        confidence: Double = 0.8,
        fingerprint: String = id,
    ) = Finding(
        path = "src/$id.kt",
        startLine = null,
        line = 1,
        severity = severity,
        category = Category.BUG,
        title = id,
        body = "body",
        suggestion = null,
        confidence = confidence,
        fingerprint = fingerprint,
    )

    @Test
    fun `removes findings already published on the pull request`() {
        Deduplicator
            .deduplicate(listOf(finding("a"), finding("b")), alreadyPublished = setOf("a"))
            .map { it.title } shouldContainExactly listOf("b")
    }

    @Test
    fun `keeps one finding per fingerprint, the most severe then the most confident`() {
        val findings =
            listOf(
                finding("minor", Severity.MINOR, 0.99, fingerprint = "same"),
                finding("major-low", Severity.MAJOR, 0.7, fingerprint = "same"),
                finding("major-high", Severity.MAJOR, 0.9, fingerprint = "same"),
                finding("other"),
            )

        Deduplicator.deduplicate(findings, alreadyPublished = emptySet()).map { it.title } shouldContainExactly
            listOf("major-high", "other")
    }

    @Test
    fun `ranks by severity then confidence and caps the inline comments`() {
        val anchored =
            listOf(
                finding("minor", Severity.MINOR, 0.95),
                finding("critical", Severity.CRITICAL, 0.7),
                finding("major-low", Severity.MAJOR, 0.65),
                finding("major-high", Severity.MAJOR, 0.9),
            )
        val unanchored = listOf(finding("elsewhere", Severity.CRITICAL, 0.9))

        val ranked = FindingRanker.rank(anchored, unanchored, maxInline = 2)

        ranked.inline.map { it.title } shouldContainExactly listOf("critical", "major-high")
        ranked.summary.map { it.title } shouldContainExactly listOf("elsewhere", "major-low", "minor")
    }
}
