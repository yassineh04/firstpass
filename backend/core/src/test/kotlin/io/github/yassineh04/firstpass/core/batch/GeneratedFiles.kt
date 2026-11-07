package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.kotest.matchers.nulls.shouldNotBeNull

private const val GAP_BETWEEN_HUNKS = 10

/** A file made of added-line hunks, one per entry of [hunkSizes], with lines of [lineLength] characters. */
fun generatedFile(
    path: String,
    vararg hunkSizes: Int,
    lineLength: Int = 40,
): AnnotatedFile = generatedFile(path, hunkSizes.map { size -> List(size) { lineLength } })

/** A file whose hunks are given as lists of line lengths. */
fun generatedFile(
    path: String,
    hunks: List<List<Int>>,
): AnnotatedFile {
    var start = 1
    val patch =
        hunks.joinToString("\n") { lineLengths ->
            val hunk =
                "@@ -$start,0 +$start,${lineLengths.size} @@\n" +
                    lineLengths.joinToString("\n") { "+" + "x".repeat(it) }
            start += lineLengths.size + GAP_BETWEEN_HUNKS
            hunk
        }
    return DiffAnnotator.annotate(DiffFile(path, FileStatus.MODIFIED, patch)).shouldNotBeNull()
}
