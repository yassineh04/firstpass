package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.Severity
import kotlin.time.Duration

/** Small Markdown and wording helpers shared by the formatters. */
internal object Markdown {
    private val CODE_SPAN = Regex("`[^`\n]*`")
    private val SPACES = Regex("""\s+""")
    private const val MAX_CODE = 200
    private const val SECONDS_PER_MINUTE = 60

    /**
     * Escapes `<` outside code spans. The text was already cleaned of unsafe HTML; this keeps
     * generics such as `Map<String, Int>` from being read as unknown tags and dropped by GitHub.
     */
    fun escapeOutsideCode(text: String): String {
        val result = StringBuilder()
        var start = 0
        for (span in CODE_SPAN.findAll(text)) {
            result.append(text.substring(start, span.range.first).replace("<", "&lt;")).append(span.value)
            start = span.range.last + 1
        }
        return result.append(text.substring(start).replace("<", "&lt;")).toString()
    }

    /** A value from the repository or a parser as inline code: no backtick or line break can end it early. */
    fun code(text: String): String =
        "`" +
            text
                .replace("`", "")
                .replace(SPACES, " ")
                .trim()
                .take(MAX_CODE) + "`"

    /** Text inside an HTML element, such as the summary line of a details block. */
    fun html(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun number(
        value: Int,
        texts: PublishTexts,
    ): String = String.format(texts.locale, "%,d", value)

    /** `1 critical, 2 minor`: the count of each severity present, most severe first. */
    fun breakdown(
        findings: List<Finding>,
        texts: PublishTexts,
    ): String =
        Severity.entries
            .mapNotNull { severity ->
                val count = findings.count { it.severity == severity }
                if (count == 0) null else "${number(count, texts)} ${texts.severityCount.getValue(severity).of(count)}"
            }.joinToString(", ")

    /** `3 issues`. */
    fun issues(
        count: Int,
        texts: PublishTexts,
    ): String = "${number(count, texts)} ${texts.issue.of(count)}"

    /** Items separated by commas, with at most [limit] shown and the number of the others. */
    fun list(
        items: List<String>,
        limit: Int,
        texts: PublishTexts,
    ): String {
        val shown = items.take(limit).joinToString(", ")
        return if (items.size > limit) "$shown, ${texts.more(number(items.size - limit, texts))}" else shown
    }

    /** `4 s` or `1 min 12 s`; a review never takes less than a second. */
    fun duration(value: Duration): String {
        val seconds = value.inWholeSeconds.coerceAtLeast(1)
        val minutes = seconds / SECONDS_PER_MINUTE
        return if (minutes > 0) "$minutes min ${seconds % SECONDS_PER_MINUTE} s" else "$seconds s"
    }

    /** `path:42`, or `path:40-42` for a multi-line finding. */
    fun location(finding: Finding): String {
        val lines = finding.startLine?.let { "$it-${finding.line}" } ?: "${finding.line}"
        return "${finding.path}:$lines"
    }
}
