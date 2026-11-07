package io.github.yassineh04.firstpass.core.diff

/** One line of a hunk, with its line numbers in the old and/or new version of the file. */
sealed interface DiffLine {
    val content: String
}

/** A line present in both versions. */
data class ContextLine(
    val oldLine: Int,
    val newLine: Int,
    override val content: String,
) : DiffLine

/** A line added by the pull request; [newLine] is its number in the new file. */
data class AddedLine(
    val newLine: Int,
    override val content: String,
) : DiffLine

/** A line removed by the pull request; [oldLine] is its number in the old file. */
data class RemovedLine(
    val oldLine: Int,
    override val content: String,
) : DiffLine

/** A block of changes introduced by a `@@ -oldStart,oldCount +newStart,newCount @@` header. */
data class Hunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

/** The same line with another content and the same numbers (used to redact or shorten a line). */
internal fun DiffLine.withContent(content: String): DiffLine =
    when (this) {
        is AddedLine -> copy(content = content)
        is ContextLine -> copy(content = content)
        is RemovedLine -> copy(content = content)
    }
