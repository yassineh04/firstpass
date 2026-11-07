package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AddedLine
import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class BatchPlannerTest {
    private fun budget(
        contentTokens: Int,
        maxBatches: Int = 6,
    ) = ProviderBudget(inputTokensPerBatch = OVERHEAD + contentTokens, maxBatches = maxBatches)

    private fun plan(
        budget: ProviderBudget,
        vararg files: AnnotatedFile,
    ) = BatchPlanner(budget, OVERHEAD).plan(files.toList())

    private fun tokens(vararg files: AnnotatedFile) = files.sumOf { TokenEstimator.estimate(it) }

    private fun Batch.paths() = files.map { it.file.path }

    private val a = generatedFile("A.kt", 15)
    private val b = generatedFile("B.kt", 15)
    private val c = generatedFile("C.kt", 15)
    private val big = generatedFile("Big.kt", 20, 20, 20)

    @Test
    fun `packs files in order while they fit, and counts the prompt overhead`() {
        val result = plan(budget(tokens(a, b)), a, b)

        result.batches.map { it.paths() } shouldContainExactly listOf(listOf("A.kt", "B.kt"))
        result.batches[0].estimatedTokens shouldBe OVERHEAD + tokens(a, b)
        result.truncated.shouldBeEmpty()
        result.notReviewed.shouldBeEmpty()
    }

    @Test
    fun `opens a new batch when the next file does not fit`() {
        val result = plan(budget(tokens(a, b) - 1), a, b)

        result.batches.map { it.paths() } shouldContainExactly listOf(listOf("A.kt"), listOf("B.kt"))
    }

    @Test
    fun `reserves room for the prompt in every batch`() {
        val input = ProviderBudget(inputTokensPerBatch = tokens(a, b) + MIN_CONTENT, maxBatches = 6)

        BatchPlanner(input, overheadTokens = MIN_CONTENT).plan(listOf(a, b)).batches shouldHaveSize 1
        BatchPlanner(input, overheadTokens = MIN_CONTENT + 1).plan(listOf(a, b)).batches shouldHaveSize 2
    }

    @Test
    fun `splits a file larger than the budget at hunk boundaries`() {
        val firstTwoHunks = AnnotatedFile(big.file, big.hunks.take(2))

        val result = plan(budget(TokenEstimator.estimate(firstTwoHunks)), big)

        result.batches.map { batch ->
            batch.files
                .single()
                .hunks
                .map { it.header }
        } shouldContainExactly
            listOf(big.hunks.take(2).map { it.header }, listOf(big.hunks[2].header))
        result.truncated.shouldBeEmpty()
        result.notReviewed.shouldBeEmpty()
    }

    @Test
    fun `truncates a hunk larger than a whole batch and reports it`() {
        val huge = generatedFile("Huge.kt", 500)
        val budget = budget(400)

        val result = plan(budget, huge)

        val kept =
            result.batches
                .single()
                .files
                .single()
                .hunks
                .single()
                .lines
        kept.size shouldBeLessThan 500
        kept.map { (it as AddedLine).newLine } shouldContainExactly (1..kept.size).toList()
        result.batches.single().estimatedTokens shouldBeLessThanOrEqual budget.inputTokensPerBatch
        result.truncated shouldContainExactly listOf(TruncatedHunk("Huge.kt", huge.hunks[0].header))
    }

    @Test
    fun `cuts a single line longer than a whole batch`() {
        val long = generatedFile("Long.kt", 1, lineLength = 5000)

        val result = plan(budget(400), long)

        val kept =
            result.batches
                .single()
                .files
                .single()
                .hunks
                .single()
                .lines
                .single()
                .content
        kept.length shouldBeLessThan 5000
        long.hunks[0].lines[0].content shouldStartWith kept
        result.truncated shouldHaveSize 1
    }

    @Test
    fun `lists the files beyond the last batch as not reviewed`() {
        val result = plan(budget(tokens(a), maxBatches = 1), a, b, c)

        result.batches.map { it.paths() } shouldContainExactly listOf(listOf("A.kt"))
        result.notReviewed shouldContainExactly
            listOf(NotReviewedFile("B.kt", partial = false), NotReviewedFile("C.kt", partial = false))
    }

    @Test
    fun `marks a split file as partially reviewed when the batch limit cuts it`() {
        val firstTwoHunks = AnnotatedFile(big.file, big.hunks.take(2))

        val result = plan(budget(TokenEstimator.estimate(firstTwoHunks), maxBatches = 1), big)

        result.batches shouldHaveSize 1
        result.notReviewed shouldContainExactly listOf(NotReviewedFile("Big.kt", partial = true))
    }

    @Test
    fun `plans nothing for no file`() {
        val result = plan(budget(1000))

        result.batches.shouldBeEmpty()
        result.notReviewed.shouldBeEmpty()
    }

    @Test
    fun `rejects a budget that leaves too little room for code`() {
        shouldThrow<IllegalArgumentException> {
            BatchPlanner(ProviderBudget(inputTokensPerBatch = 400, maxBatches = 3), overheadTokens = 300)
        }
    }

    private companion object {
        const val OVERHEAD = 300
        const val MIN_CONTENT = 200
    }
}
