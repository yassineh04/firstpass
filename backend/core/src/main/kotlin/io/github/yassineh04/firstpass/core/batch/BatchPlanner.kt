package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.diff.DiffLine
import io.github.yassineh04.firstpass.core.diff.Hunk
import io.github.yassineh04.firstpass.core.diff.renderLine
import io.github.yassineh04.firstpass.core.diff.withContent

/** Token limits of one model provider. The llm adapter sets the values of each provider. */
data class ProviderBudget(
    val inputTokensPerBatch: Int,
    val maxBatches: Int,
    val outputTokens: Int = 1500,
)

/** Files, or parts of files, sent to the model in one request. [estimatedTokens] includes the prompt. */
data class Batch(
    val files: List<AnnotatedFile>,
    val estimatedTokens: Int,
)

/** A hunk cut to fit a batch; its end is not reviewed. */
data class TruncatedHunk(
    val path: String,
    val header: String,
)

/** A file left out because no batch was left; [partial] when some of its hunks were sent. */
data class NotReviewedFile(
    val path: String,
    val partial: Boolean,
)

data class BatchPlan(
    val batches: List<Batch>,
    val truncated: List<TruncatedHunk>,
    val notReviewed: List<NotReviewedFile>,
)

/**
 * Splits the files to review into batches that fit the input budget of a provider.
 *
 * Files keep their order (the file filter already sorted them by priority). Each batch
 * reserves [overheadTokens] for the prompt itself. A file larger than a batch is split at hunk
 * boundaries, and a hunk larger than a batch is truncated. Files that come after the last
 * allowed batch are reported as not reviewed.
 */
class BatchPlanner(
    private val budget: ProviderBudget,
    private val overheadTokens: Int,
) {
    private val contentBudget = budget.inputTokensPerBatch - overheadTokens

    init {
        require(budget.maxBatches >= 1) { "At least one batch is needed, got ${budget.maxBatches}" }
        require(contentBudget >= MIN_CONTENT_TOKENS) {
            "The budget leaves $contentBudget tokens for code; at least $MIN_CONTENT_TOKENS are needed"
        }
    }

    fun plan(files: List<AnnotatedFile>): BatchPlan {
        val truncated = mutableListOf<TruncatedHunk>()
        val piecesOfFiles = files.map { split(it, truncated) }
        val packer = Packer(budget.maxBatches, contentBudget)
        val sentPieces = IntArray(files.size)
        filesLoop@ for ((index, pieces) in piecesOfFiles.withIndex()) {
            for (piece in pieces) {
                if (!packer.add(piece)) break@filesLoop
                sentPieces[index]++
            }
        }
        val notReviewed =
            files.indices
                .filter { sentPieces[it] < piecesOfFiles[it].size }
                .map { NotReviewedFile(files[it].file.path, partial = sentPieces[it] > 0) }
        return BatchPlan(packer.batches(overheadTokens), truncated, notReviewed)
    }

    /** Cuts a file into pieces that each fit in one batch, grouping as many hunks as possible. */
    private fun split(
        file: AnnotatedFile,
        truncated: MutableList<TruncatedHunk>,
    ): List<Piece> {
        val whole = TokenEstimator.estimate(file)
        if (whole <= contentBudget) return listOf(Piece(file, whole))

        val pieces = mutableListOf<Piece>()
        var group = emptyList<Hunk>()
        for (hunk in file.hunks) {
            val grown = group + hunk
            group =
                when {
                    fits(file, grown) -> {
                        grown
                    }

                    fits(file, listOf(hunk)) -> {
                        pieces += piece(file, group)
                        listOf(hunk)
                    }

                    else -> {
                        if (group.isNotEmpty()) pieces += piece(file, group)
                        pieces += truncate(file, hunk, truncated)
                        emptyList()
                    }
                }
        }
        if (group.isNotEmpty()) pieces += piece(file, group)
        return pieces
    }

    /** Keeps the first lines of a hunk that fit in one batch; a single huge line is shortened. */
    private fun truncate(
        file: AnnotatedFile,
        hunk: Hunk,
        truncated: MutableList<TruncatedHunk>,
    ): Piece {
        truncated += TruncatedHunk(file.file.path, hunk.header)
        val maxChars = TokenEstimator.maxChars(contentBudget - TokenEstimator.fileOverhead(file.file.path))
        var chars = hunk.header.length
        val kept = mutableListOf<DiffLine>()
        for (line in hunk.lines) {
            val lineChars = 1 + renderLine(line).length
            if (chars + lineChars > maxChars) break
            kept += line
            chars += lineChars
        }
        if (kept.isEmpty() && hunk.lines.isNotEmpty()) {
            val first = hunk.lines.first()
            val decoration = 1 + renderLine(first).length - first.content.length
            kept += first.withContent(first.content.take((maxChars - chars - decoration).coerceAtLeast(0)))
        }
        return piece(file, listOf(hunk.copy(lines = kept)))
    }

    private fun fits(
        file: AnnotatedFile,
        hunks: List<Hunk>,
    ): Boolean = TokenEstimator.estimate(AnnotatedFile(file.file, hunks)) <= contentBudget

    private fun piece(
        file: AnnotatedFile,
        hunks: List<Hunk>,
    ): Piece = AnnotatedFile(file.file, hunks).let { Piece(it, TokenEstimator.estimate(it)) }

    private class Piece(
        val file: AnnotatedFile,
        val tokens: Int,
    )

    /** Fills batches in order; a new batch opens when the next piece does not fit. */
    private class Packer(
        private val maxBatches: Int,
        private val capacity: Int,
    ) {
        private val closed = mutableListOf<List<Piece>>()
        private var current = mutableListOf<Piece>()
        private var used = 0

        /** Returns false when the piece needs a new batch and none is left. */
        fun add(piece: Piece): Boolean {
            if (current.isNotEmpty() && used + piece.tokens > capacity) {
                closed += current
                current = mutableListOf()
                used = 0
            }
            if (closed.size >= maxBatches) return false
            current += piece
            used += piece.tokens
            return true
        }

        fun batches(overheadTokens: Int): List<Batch> =
            (closed + listOfNotNull(current.takeIf { it.isNotEmpty() })).map { pieces ->
                Batch(mergeSameFile(pieces), overheadTokens + pieces.sumOf { it.tokens })
            }

        /** Consecutive pieces of one file in the same batch are shown as a single file. */
        private fun mergeSameFile(pieces: List<Piece>): List<AnnotatedFile> {
            val files = mutableListOf<AnnotatedFile>()
            for (piece in pieces) {
                val last = files.lastOrNull()
                if (last != null && last.file.path == piece.file.file.path) {
                    files[files.lastIndex] = AnnotatedFile(last.file, last.hunks + piece.file.hunks)
                } else {
                    files += piece.file
                }
            }
            return files
        }
    }

    private companion object {
        const val MIN_CONTENT_TOKENS = 200
    }
}
