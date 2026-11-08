package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.eval.Numbers.NOT_AVAILABLE
import io.github.yassineh04.firstpass.eval.Numbers.decimal
import io.github.yassineh04.firstpass.eval.Numbers.median
import io.github.yassineh04.firstpass.eval.Numbers.percent
import io.github.yassineh04.firstpass.eval.Numbers.twoDecimals
import java.time.LocalDate

/** What the report says about the run itself. */
internal data class EvalSettings(
    val date: LocalDate,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val runs: Int,
)

/**
 * The Markdown report of an evaluation, versioned in `eval/results/`. Sections are built line by
 * line: trimMargin would also strip the leading "|" of interpolated table rows.
 */
internal object EvalReport {
    private const val NO_ANSWER = "No model call got an answer, so there is nothing to compare."
    private val UNSAFE_IN_FILE_NAME = Regex("[^A-Za-z0-9._-]+")

    fun markdown(
        settings: EvalSettings,
        runs: List<CaseRun>,
        calls: List<CallRecord>,
    ): String =
        listOf(
            header(settings, runs),
            metricsSection(Metrics.of(runs)),
            caseSection(runs),
            detailSection(runs),
            tokenSection(calls),
        ).joinToString("\n\n", postfix = "\n")

    /** `<date>-<model>.md`, with characters unsafe in file names replaced. */
    fun fileName(settings: EvalSettings): String =
        "${settings.date}-${settings.model.replace(UNSAFE_IN_FILE_NAME, "-").trim('-')}.md"

    private fun header(
        settings: EvalSettings,
        runs: List<CaseRun>,
    ): String =
        listOf(
            "# Evaluation of ${settings.date}",
            "",
            "Provider ${settings.provider}, model ${settings.model}, prompt version ${settings.promptVersion}: " +
                "${runs.map { it.case.id }.distinct().size} cases, ${settings.runs} run(s) each.",
            "Counts are summed over the runs. Reviews that did not complete are left out of the metrics: " +
                "they measure availability, not quality.",
            "A finding matches an expected problem on the same file, in the same category, " +
                "at most ${Matcher.LINE_TOLERANCE} lines away.",
        ).joinToString("\n")

    private fun metricsSection(metrics: Metrics): String {
        val noise = metrics.noise?.let { "${decimal(it)} findings per clean case" } ?: NOT_AVAILABLE
        val categories =
            metrics.perCategory.map {
                "| ${it.category.wireName} | ${it.expected} | ${it.found} | ${it.published} | ${it.falsePositives} |"
            }
        return (
            listOf(
                "## Metrics",
                "",
                "| Metric | Value | Target |",
                "| --- | --- | --- |",
                "| Precision | ${ratio(metrics.precision, metrics.matched, metrics.published)} | 70 % or more |",
                "| Recall | ${ratio(metrics.recall, metrics.matched, metrics.expected)} | 60 % or more |",
                "| Noise | $noise | 1 or less |",
                "",
                "## Per category",
                "",
                "| Category | Expected | Found | Published | False positives |",
                "| --- | --- | --- | --- | --- |",
            ) + categories
        ).joinToString("\n")
    }

    private fun caseSection(runs: List<CaseRun>): String {
        val rows =
            runs.groupBy { it.case.id }.map { (id, caseRuns) ->
                val expected = caseRuns.sumOf { it.case.expected.size }
                val found = caseRuns.sumOf { it.match.matched.size }
                val extra = caseRuns.sumOf { it.match.extra.size }
                "| $id | $expected | $found | $extra | ${outcome(caseRuns)} |"
            }
        val header =
            listOf(
                "## Per case",
                "",
                "| Case | Expected | Found | Extra findings | Outcome |",
                "| --- | --- | --- | --- | --- |",
            )
        return (header + rows).joinToString("\n")
    }

    private fun outcome(caseRuns: List<CaseRun>): String {
        val failed = caseRuns.sumOf { it.failedBatches }
        val outcomes = caseRuns.map { it.outcome }.distinct().joinToString(", ")
        return when (failed) {
            0 -> outcomes
            1 -> "$outcomes (1 failed batch)"
            else -> "$outcomes ($failed failed batches)"
        }
    }

    /** The problems missed and the findings that matched nothing, to judge them by hand. */
    private fun detailSection(runs: List<CaseRun>): String {
        val missed =
            runs.flatMap { run ->
                run.match.missed.map {
                    "- ${run.case.id}, run ${run.run}: ${it.path}:${it.line} (${it.category.wireName})"
                }
            }
        val extra =
            runs.flatMap { run ->
                run.match.extra.map {
                    "- ${run.case.id}, run ${run.run}: ${it.path}:${it.line} " +
                        "(${it.category.wireName}, ${it.severity.wireName}) \"${it.title}\""
                }
            }
        return listOf(
            "## Missed problems",
            "",
            missed.ifEmpty { listOf("None.") }.joinToString("\n"),
            "",
            "## Findings that matched no expected problem",
            "",
            extra.ifEmpty { listOf("None.") }.joinToString("\n"),
        ).joinToString("\n")
    }

    /** Our token estimate against the provider's count, to calibrate the batch planner. */
    private fun tokenSection(calls: List<CallRecord>): String {
        val answered = calls.mapNotNull { call -> call.reportedInputTokens?.let { call.estimatedInputTokens to it } }
        if (answered.isEmpty()) return "## Token estimate\n\n$NO_ANSWER"
        val estimated = answered.sumOf { it.first }
        val reported = answered.sumOf { it.second }
        val ratios = answered.map { (estimate, count) -> count.toDouble() / estimate }.sorted()
        return listOf(
            "## Token estimate",
            "",
            "Input tokens of the calls that got an answer: our estimate next to the count reported by the provider.",
            "The reported count also includes the JSON schema sent with the request and any repair attempt.",
            "",
            "| Calls | Estimated input | Reported input | Ratio |",
            "| --- | --- | --- | --- |",
            "| ${answered.size} | $estimated | $reported | ${twoDecimals(reported.toDouble() / estimated)} |",
            "",
            "Ratio per call: lowest ${twoDecimals(ratios.first())}, median ${twoDecimals(median(ratios))}, " +
                "highest ${twoDecimals(ratios.last())}.",
        ).joinToString("\n")
    }

    private fun ratio(
        value: Double?,
        numerator: Int,
        denominator: Int,
    ): String = value?.let { "${percent(it)} ($numerator/$denominator)" } ?: NOT_AVAILABLE
}
