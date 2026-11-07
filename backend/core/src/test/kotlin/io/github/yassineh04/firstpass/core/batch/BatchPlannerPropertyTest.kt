package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** For random files and budgets, the plan respects the budget and loses nothing silently. */
class BatchPlannerPropertyTest {
    @Test
    fun `batches respect the budget and every file is sent whole unless reported`() {
        runBlocking {
            checkAll(ITERATIONS, scenarios) { scenario ->
                val plan = BatchPlanner(scenario.budget, OVERHEAD).plan(scenario.files)

                plan.batches.size shouldBeLessThanOrEqual scenario.budget.maxBatches
                for (batch in plan.batches) {
                    batch.files.shouldNotBeEmpty()
                    batch.estimatedTokens shouldBeLessThanOrEqual scenario.budget.inputTokensPerBatch
                }

                val reported = plan.notReviewed.map { it.path }.toSet() + plan.truncated.map { it.path }.toSet()
                for (file in scenario.files.filter { it.file.path !in reported }) {
                    val sent = plan.batches.flatMap { it.files }.filter { it.file.path == file.file.path }
                    sent.flatMap { it.hunks }.flatMap { it.lines } shouldContainExactly file.hunks.flatMap { it.lines }
                }
            }
        }
    }

    private data class Scenario(
        val files: List<AnnotatedFile>,
        val budget: ProviderBudget,
    )

    private companion object {
        const val ITERATIONS = 300
        const val OVERHEAD = 300

        val lineLengths = Arb.list(Arb.int(0..400), 1..40)
        val hunks = Arb.list(lineLengths, 1..4)
        val fileHunks = Arb.list(hunks, 1..6)

        val scenarios: Arb<Scenario> =
            Arb.bind(fileHunks, Arb.int(200..3000), Arb.int(1..6)) { files, contentTokens, maxBatches ->
                Scenario(
                    files = files.mapIndexed { index, fileHunks -> generatedFile("File$index.kt", fileHunks) },
                    budget = ProviderBudget(inputTokensPerBatch = OVERHEAD + contentTokens, maxBatches = maxBatches),
                )
            }
    }
}
