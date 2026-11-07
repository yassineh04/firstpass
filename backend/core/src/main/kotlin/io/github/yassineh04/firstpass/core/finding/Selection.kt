package io.github.yassineh04.firstpass.core.finding

/** Removes findings already published on the pull request, and duplicates found by several batches. */
object Deduplicator {
    fun deduplicate(
        findings: List<Finding>,
        alreadyPublished: Set<String>,
    ): List<Finding> {
        val bestByFingerprint = LinkedHashMap<String, Finding>()
        for (finding in findings.filter { it.fingerprint !in alreadyPublished }) {
            val current = bestByFingerprint[finding.fingerprint]
            if (current == null || FindingRanker.ORDER.compare(finding, current) < 0) {
                bestByFingerprint[finding.fingerprint] = finding
            }
        }
        return bestByFingerprint.values.toList()
    }
}

/** Findings published as inline comments, and the rest, listed in the review summary. */
data class RankedFindings(
    val inline: List<Finding>,
    val summary: List<Finding>,
)

/** Orders findings by severity, then confidence, and keeps at most `maxInline` inline comments. */
object FindingRanker {
    internal val ORDER: Comparator<Finding> =
        compareBy<Finding> { it.severity.ordinal }
            .thenByDescending { it.confidence }
            .thenBy { it.path }
            .thenBy { it.line }

    fun rank(
        anchored: List<Finding>,
        unanchored: List<Finding>,
        maxInline: Int,
    ): RankedFindings {
        val sorted = anchored.sortedWith(ORDER)
        return RankedFindings(
            inline = sorted.take(maxInline),
            summary = (unanchored + sorted.drop(maxInline)).sortedWith(ORDER),
        )
    }
}
