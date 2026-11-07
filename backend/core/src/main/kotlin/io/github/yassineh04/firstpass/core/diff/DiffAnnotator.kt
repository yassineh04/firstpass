package io.github.yassineh04.firstpass.core.diff

/** Which diff lines may receive a review comment (`comment_on` setting). */
enum class CommentOn {
    ADDED_LINES,
    DIFF_CONTEXT,
}

/**
 * A file ready for review: its numbered hunks, the annotated text sent to the model, and the
 * lines that may receive a comment. GitHub only accepts comments on lines present in the diff.
 */
class AnnotatedFile(
    val file: DiffFile,
    val hunks: List<Hunk>,
) {
    private val addedLineHunks: Map<Int, Int> = indexLines<AddedLine> { it.newLine }
    private val contextLineHunks: Map<Int, Int> = indexLines<ContextLine> { it.newLine }

    /**
     * The hunks as the model sees them: `+ 42 | code` for an added line, `  40 | code` for a
     * context line and `- | code` for a removed line, which has no number in the new file.
     */
    fun render(): String =
        hunks.joinToString("\n") { hunk ->
            (listOf(hunk.header) + hunk.lines.map(::renderLine)).joinToString("\n")
        }

    /** Content of a new-file line shown in the diff (added or context line), or null if absent. */
    fun contentAt(newLine: Int): String? =
        hunks.firstNotNullOfOrNull { hunk ->
            hunk.lines.firstNotNullOfOrNull { line ->
                when (line) {
                    is AddedLine -> line.content.takeIf { line.newLine == newLine }
                    is ContextLine -> line.content.takeIf { line.newLine == newLine }
                    is RemovedLine -> null
                }
            }
        }

    fun isCommentable(
        line: Int,
        commentOn: CommentOn,
    ): Boolean = hunkOf(line, commentOn) != null

    /** A multi-line comment needs both ends commentable, in order, inside the same hunk. */
    fun isCommentableRange(
        startLine: Int,
        endLine: Int,
        commentOn: CommentOn,
    ): Boolean {
        val startHunk = hunkOf(startLine, commentOn)
        return startLine < endLine && startHunk != null && startHunk == hunkOf(endLine, commentOn)
    }

    private fun hunkOf(
        line: Int,
        commentOn: CommentOn,
    ): Int? = addedLineHunks[line] ?: contextLineHunks[line].takeIf { commentOn == CommentOn.DIFF_CONTEXT }

    /** Maps the new-file number of every line of type [T] to the index of its hunk. */
    private inline fun <reified T : DiffLine> indexLines(crossinline number: (T) -> Int): Map<Int, Int> =
        buildMap {
            hunks.forEachIndexed { hunkIndex, hunk ->
                hunk.lines.filterIsInstance<T>().forEach { put(number(it), hunkIndex) }
            }
        }
}

/** One line as the model sees it; shared with the batch planner, which measures rendered text. */
internal fun renderLine(line: DiffLine): String =
    when (line) {
        is AddedLine -> "+ ${line.newLine} | ${line.content}"
        is ContextLine -> "  ${line.newLine} | ${line.content}"
        is RemovedLine -> "- | ${line.content}"
    }

object DiffAnnotator {
    /** Returns null when the file has nothing to review (no patch, or a patch without hunks). */
    fun annotate(file: DiffFile): AnnotatedFile? {
        val hunks = file.patch?.let(HunkParser::parse).orEmpty()
        return if (hunks.isEmpty()) null else AnnotatedFile(file, hunks)
    }
}
