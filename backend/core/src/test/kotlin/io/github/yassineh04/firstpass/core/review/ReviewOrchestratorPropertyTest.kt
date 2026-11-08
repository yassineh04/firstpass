package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.batch.generatedFile
import io.github.yassineh04.firstpass.core.diff.AddedLine
import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * For random files, providers and failures: every prompt fits the budget of the provider it is
 * sent to, and no added line is dropped silently. Each one reaches a model that answers, or its
 * file is reported (skipped, not reviewed, truncated or in a failed batch).
 */
class ReviewOrchestratorPropertyTest {
    @Test
    fun `prompts fit their provider and no added line is lost, whatever fails`() {
        runBlocking {
            checkAll(ITERATIONS, scenarios) { scenario ->
                val sent = mutableListOf<Sent>()
                val providers =
                    scenario.providers.mapIndexed { index, setup ->
                        val steps = ArrayDeque(setup.steps)
                        val llm =
                            LlmPort { request ->
                                val step = steps.removeFirstOrNull() ?: Step.OK
                                sent += Sent(setup.budget, request, answered = step == Step.OK)
                                step.result("p$index")
                            }
                        ReviewProvider("p$index", llm, setup.budget, tokensPerDay = 10_000_000)
                    }

                val outcome =
                    ReviewOrchestrator(providers, QuotaPolicy(QuotaLimits(), CLOCK))
                        .review(ReviewRequest(PR, scenario.files.map { it.file }))

                for (call in sent) {
                    call.tokens() shouldBeLessThanOrEqual call.budget.inputTokensPerBatch
                }
                sent.size shouldBeLessThanOrEqual scenario.providers.sumOf { it.budget.maxBatches }

                val report = outcome.report()
                val excused =
                    report.skipped.map { it.path } + report.notReviewed.map { it.path } +
                        report.truncated.map { it.path } + report.failedBatches.flatMap { it.paths }
                val answeredLines = sent.filter { it.answered }.flatMap { it.addedLines() }.toSet()
                for (file in scenario.files.filter { it.file.path !in excused }) {
                    withClue(file.file.path) { answeredLines shouldContainAll file.addedLines() }
                }
            }
        }
    }

    private enum class Step {
        OK,
        RATE_LIMITED,
        UNAVAILABLE,
        REJECTED,
        INVALID_OUTPUT,
        ;

        fun result(provider: String): LlmResult =
            if (this == OK) {
                LlmResult.Success(ModelReview("Reviewed.", emptyList()), provider, "model", 10, 10)
            } else {
                LlmResult.Failure(LlmFailure.valueOf(name), provider, "failed")
            }
    }

    private class Sent(
        val budget: ProviderBudget,
        val request: LlmRequest,
        val answered: Boolean,
    ) {
        fun tokens() = TokenEstimator.estimate(request.prompt.system) + TokenEstimator.estimate(request.prompt.user)

        /** (path, line) of every added line shown in the prompt, read back from the annotated diff. */
        fun addedLines(): List<Pair<String, Int>> {
            var path = ""
            return request.prompt.user.lines().mapNotNull { line ->
                FILE_HEADER.matchEntire(line)?.let { path = it.groupValues[1] }
                ADDED_LINE.find(line)?.let { path to it.groupValues[1].toInt() }
            }
        }
    }

    private class ProviderSetup(
        val budget: ProviderBudget,
        val steps: List<Step>,
    )

    private class Scenario(
        val files: List<AnnotatedFile>,
        val providers: List<ProviderSetup>,
    )

    private fun AnnotatedFile.addedLines(): List<Pair<String, Int>> =
        hunks.flatMap { it.lines }.filterIsInstance<AddedLine>().map { file.path to it.newLine }

    private fun ReviewOutcome.report(): ReviewReport =
        when (this) {
            is ReviewOutcome.Completed -> report
            is ReviewOutcome.Unavailable -> report
            is ReviewOutcome.QuotaExceeded -> error("No quota should be reached here: $reason")
        }

    private companion object {
        const val ITERATIONS = 200
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC)
        val PR = PullRequestInfo(number = 1, title = "Random change", description = null)
        val OVERHEAD = PromptBuilder(ReviewSettings()).overheadTokens(PR)
        val FILE_HEADER = Regex("""=== File: (\S+) \(.*""")
        val ADDED_LINE = Regex("""^\+ (\d+) \| """)

        val lineLengths = Arb.list(Arb.int(0..200), 1..20)
        val hunks = Arb.list(lineLengths, 1..3)
        val files = Arb.list(hunks, 1..5)

        val providerSetups =
            Arb.bind(Arb.int(200..2500), Arb.int(1..4), Arb.list(Arb.element(Step.entries), 0..6)) {
                contentTokens,
                maxBatches,
                steps,
                ->
                ProviderSetup(ProviderBudget(OVERHEAD + contentTokens, maxBatches), steps)
            }

        val scenarios: Arb<Scenario> =
            Arb.bind(files, Arb.list(providerSetups, 1..3)) { fileHunks, providers ->
                Scenario(
                    files = fileHunks.mapIndexed { index, hunksOfFile -> generatedFile("File$index.kt", hunksOfFile) },
                    providers = providers,
                )
            }
    }
}
